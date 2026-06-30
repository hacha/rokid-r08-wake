// r08waked - R08 ring "single tap" (KEY_PLAYPAUSE) wake helper for Rokid glasses.
// Runs as shell uid. Reads the evdev device directly (no getevent / no stdio
// buffering). Re-opens if the ring BLE drops/reconnects.
//
// Media-key suppression: the ring emits KEY_PLAYPAUSE, which the glasses would
// otherwise forward to a paired phone's media session (AVRCP) -> e.g. Apple Music
// starts playing on a tap-to-wake. So whenever the panel is OFF we EVIOCGRAB the
// device (exclusive): no tap can reach the glasses media path, we just wake.
// While the panel is ON we release the grab so R08-Access-Bridge handles nav.
//
// The grab is released ONLY after the screen is *confirmed* on (not merely after
// we send WAKEUP) -- otherwise a double-tap (which doesn't fully wake) would leave
// us ungrabbed while the panel is still off, and the next tap would leak to media.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#ifndef KEY_PLAYPAUSE
#define KEY_PLAYPAUSE 164
#endif

#define POLL_MS       2000 // screen-state poll cadence while the panel is on (idle)
#define WAKE_WAIT_MS  1500 // after WAKEUP, how long to wait for the panel to come on
#define WAKE_STEP_MS  150

// 1 if display is on (mWakefulness=Awake), 0 if off/doze.
static int screen_on(void) {
    FILE *p = popen("dumpsys power 2>/dev/null", "r");
    if (!p) return 1; // unknown -> assume on (do nothing)
    char line[512];
    int on = 1;
    while (fgets(line, sizeof line, p)) {
        char *w = strstr(line, "mWakefulness=");
        if (w) { on = (strstr(w, "Awake") != NULL); break; }
    }
    pclose(p);
    return on;
}

// Poll screen_on() up to timeout_ms; return 1 as soon as it is on, else 0.
static int wait_screen_on(int timeout_ms) {
    int waited = 0;
    for (;;) {
        if (screen_on()) return 1;
        if (waited >= timeout_ms) return 0;
        usleep(WAKE_STEP_MS * 1000);
        waited += WAKE_STEP_MS;
    }
}

// Open the "R08 ... Consumer Control" evdev device by name (index can change).
static int open_ring(char *out, int outlen) {
    DIR *d = opendir("/dev/input");
    if (!d) return -1;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDWR);     // O_RDWR for reliable EVIOCGRAB
        if (fd < 0) fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof name), name) >= 0 &&
            strstr(name, "R08") && strstr(name, "Consumer")) {
            if (out) snprintf(out, outlen, "%s", path);
            closedir(d);
            return fd;
        }
        close(fd);
    }
    closedir(d);
    return -1;
}

static void set_grab(int fd, int on) { ioctl(fd, EVIOCGRAB, on ? 1 : 0); }

// Discard any queued events (stale taps from before a grab / the 2nd tap of a
// wake double-tap).
static void drain(int fd) {
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl < 0) return;
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    struct input_event e;
    while (read(fd, &e, sizeof e) == (ssize_t) sizeof e) {}
    fcntl(fd, F_SETFL, fl);
}

int main(void) {
    setvbuf(stderr, NULL, _IOLBF, 0);
    for (;;) {
        char path[64] = "?";
        int fd = open_ring(path, sizeof path);
        if (fd < 0) { sleep(3); continue; }

        int grabbed = !screen_on();   // grab iff panel currently off
        set_grab(fd, grabbed);
        if (grabbed) drain(fd);
        fprintf(stderr, "r08waked: watching %s (grabbed=%d)\n", path, grabbed);

        struct input_event ev;
        int alive = 1;
        while (alive) {
            if (grabbed) {
                // panel OFF: blocking read (suspend-friendly). The grab keeps every
                // tap off the media path. On a tap we WAKEUP, then release the grab
                // ONLY if the panel actually came on; otherwise stay grabbed.
                ssize_t n = read(fd, &ev, sizeof ev);
                if (n != (ssize_t) sizeof ev) { alive = 0; break; }
                if (ev.type == EV_KEY && ev.code == KEY_PLAYPAUSE && ev.value == 1) {
                    fprintf(stderr, "r08waked: tap while off -> WAKEUP (media suppressed)\n");
                    system("input keyevent 224"); // KEYCODE_WAKEUP
                    if (wait_screen_on(WAKE_WAIT_MS)) {
                        set_grab(fd, 0);
                        drain(fd);   // drop the 2nd tap of a double-tap, etc.
                        grabbed = 0;
                    } else {
                        fprintf(stderr, "r08waked: panel still off -> stay grabbed\n");
                        drain(fd);   // drop queued taps; remain grabbed (no leak)
                    }
                }
            } else {
                // panel ON: not grabbed -> Access Bridge gets taps. Drain our copy
                // so poll() can block, then watch for the OFF transition.
                drain(fd);
                struct pollfd pfd = { fd, POLLIN, 0 };
                int pr = poll(&pfd, 1, POLL_MS);
                if (pr < 0) { if (errno == EINTR) continue; alive = 0; break; }
                if (pr == 0) { // idle timeout -> check screen
                    if (!screen_on()) {
                        set_grab(fd, 1);
                        drain(fd);
                        grabbed = 1;
                        fprintf(stderr, "r08waked: screen off -> grab\n");
                    }
                }
                // pr > 0: events pending (user active) -> loop & drain; screen is on
            }
        }
        set_grab(fd, 0);
        close(fd);
        sleep(1);
    }
    return 0;
}
