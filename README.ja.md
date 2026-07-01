# RokidR08Wake

*[English](README.md) · **日本語***

R08 スマートリングの **単発タップで Rokid Glasses をスリープ復帰**させる、shell uid で動く小さな native 常駐ヘルパー。あわせて、ペアした iPhone 等で **Apple Music が暴発する問題**も抑止する。

リングのナビ操作そのものは [Anezium/R08-Access-Bridge](https://github.com/Anezium/R08-Access-Bridge)（リング→グラス直 BLE）が担う。本ツールはそこに**無い**「画面 OFF からの単タップ wake」だけを補い、R08-Access-Bridge と共存する。

> 実機検証は Rokid Glasses（`ro.product.model=RG-glasses`, YodaOS-Sprite / Android 12 系, arm64）＋ R08 ring（"R08_4B00"）で 2026-06-30 に実施。

---

## 使い方

前提：グラスに R08-Access-Bridge が入っていてリングがナビとして使えていること（本ツールは wake だけを足す）。用途に応じて A か B を選ぶ。

### A. 実運用：再起動しても復活（推奨）

グラス単独で自分を立て直す自己 arm アプリ `armapp/`（`com.hacha.rokidr08wake`）を使う。**companion・常時 USB・Wi-Fi 不要。**

**一度だけのセットアップ（USB 一回、以後不要）**

1. デーモンをビルド：`./build.sh` → `./r08waked`（armapp が assets に同梱する）
2. 再起動後も loopback で adb 待受を残す：
   ```sh
   adb -s <glasses> shell setprop persist.adb.tcp.port 5555
   ```
3. `armapp` をビルドしてグラスに install（信頼済み adb 鍵と `r08waked` を assets に同梱。ビルド手順・鍵の抽出は下記 [永続化の詳細](#永続化再起動対応の詳細armapp) 参照）。APK は各自ビルドする（信頼済み adb 秘密鍵を内蔵するため配布はしない。下記の警告参照）。

**日常運用**

- **再起動したら、グラスで「R08 Wake」を 1 回開くだけ**。アプリが loopback 127.0.0.1:5555 に adb 接続 → shell で `r08waked` を deploy＆`setsid` 起動 → wake 復活。
- 一度立ち上がれば adb 切断・自律サスペンドは生き残る。次の reboot までアプリを触る必要はない。
- （BootReceiver での完全ゼロタッチ自動化は、この ROM がサードパーティのバックグラウンド起動を拒否するため**不可**。「1 回開く」が最小の手動ステップ。）

### B. 手動で今すぐ動かす（開発・お試し）

reboot 耐性は要らず、その場で動かしたいとき：

```sh
./build.sh          # aarch64 にクロスコンパイル → ./r08waked
./arm-r08wake.sh    # RG-glasses を adb 特定 → push → setsid 起動 → ログ＆生存確認
```

`setsid` 起動なので **adb 切断・自律サスペンドは生き残る**が、**reboot では消える**（そのときは A のアプリを開くか、この 2 コマンドを再実行）。

### 動作確認

```sh
GL=$(adb devices | awk 'NR>1&&$2=="device"{print $1}' | while read s; do \
  [ "$(adb -s $s shell getprop ro.product.model|tr -d '\r')" = RG-glasses ] && echo $s; done)
adb -s "$GL" shell input keyevent 223          # 画面OFF
# リングを1タップ → 画面が点く / ペア端末の音楽は鳴らない
adb -s "$GL" shell cat /data/local/tmp/r08waked.log   # "tap while off -> WAKEUP (media suppressed)"
```

長時間放置後はリング側 BLE が切れていることがあり、最初のタップは再接続で消費され 2 回目で点く場合がある（デーモンは自動で開き直す）。

### 停止

```sh
adb -s "$GL" shell pkill r08waked
```

---

## 仕組み（ざっくり）

- R08 の**単タップ = メディアキー `KEY_PLAYPAUSE`** が、グラス内の `/dev/input/event3`（`"R08_4B00 Consumer Control"`）にカーネルレベルで届く。**この BLE HID はカーネルの wake source** なので、AP が深くサスペンドしていてもタップで CPU が起きる。だから keepalive も wakelock も要らず電池に優しい。
- ところが**画面 OFF 中は Android フレームワークがメディアキーを握り潰す**ため、アプリや Access Bridge にはタップが届かず、wake できない。そこで **evdev を直接 read する native デーモン `r08waked`**（shell uid）でフレームワークより下でタップを拾い、`input keyevent 224`（`KEYCODE_WAKEUP`）で画面を点ける。
- 放置するとメディアキーがペア端末に転送されて **Apple Music が暴発**するので、**画面 OFF の間だけ `EVIOCGRAB` でデバイスを排他確保**して遮断する（画面 ON では解放し、Access Bridge の通常ナビを邪魔しない）。

```
   [R08 ring] --BLE HID--> [Rokid Glasses (Android)]
        |                        |
        |  KEY_PLAYPAUSE         +-- /dev/input/event3  (R08_4B00 Consumer Control)
        |  (1 tap)               |        |
        |                        |        +--> r08waked (本ツール, shell uid)
        |                        |        +--> Android input framework
        |                        |                 |
        |                        |                 +-- 画面ON: Access Bridge が onKeyEvent でナビ
        |                        |                 +-- 画面OFF: media session へ → (A2DP/AVRCP) → ペア端末
   [paired iPhone] <------BT-----+                                            ↑ ここで Apple Music 暴発
```

以下は「なぜそうなるか」の詳細。使うだけなら上まででよい。

---

## 詳しい仕組み

### 背景：解きたかった問題

Rokid Glasses は画面 OFF で省電力に入る（実測では画面 OFF 後しばらくでセンサー配信も止まる ROM）。R08 ring を R08-Access-Bridge で「グラスのナビ用コントローラー」として使えるが、**画面が消えるとリングのタップでは復帰できない**（Access Bridge に wake 機能は無い）。

目標：**リングをポケットから出さずに、単タップでグラスを復帰させる**。しかも electricity を食わない方法で。

### 1. R08 のタップはどうグラスに届くか

R08-Access-Bridge は接続時にリングを **`appType 1`（Stable mode）** に設定する。appType は R08（QRing 互換リング）の**出力モード**で、リング側に永続する。`appType 1` ではリングが **HID Consumer Control** として振る舞い、操作が次のように出る：

| 操作 | イベント | 備考 |
|---|---|---|
| シングルタップ | `KEY_PLAYPAUSE`（HID usage `0x0CD`） | 本ツールが wake トリガに使う |
| スワイプ前/後 | `KEY_NEXTSONG` / `KEY_PREVIOUSSONG` | 次/前曲キー |

`getevent -lt` で実機確認した生ログ（タップ1回）：

```
/dev/input/event3: EV_MSC  MSC_SCAN       000c00cd
/dev/input/event3: EV_KEY  KEY_PLAYPAUSE  DOWN
/dev/input/event3: EV_SYN  SYN_REPORT     00000000
/dev/input/event3: EV_MSC  MSC_SCAN       000c00cd
/dev/input/event3: EV_KEY  KEY_PLAYPAUSE  UP
/dev/input/event3: EV_SYN  SYN_REPORT     00000000
```

`/dev/input` 上のデバイス名は `"R08_4B00 Consumer Control"`。index（`event3`）は再接続で変わりうるので、本ツールは**名前で解決**する。

### 2. なぜ画面 OFF 中はタップで wake しないのか

Android は非インタラクティブ時（画面 OFF）、`PhoneWindowManager.interceptKeyBeforeQueueing` で**「wake key 以外」をディスパッチ前に捨てる**。メディアキー（`KEY_PLAYPAUSE` 等）は wake key ではなく、メディアセッションへ回されるだけで、アプリや Accessibility の `onKeyEvent` には**届かない**。

だから R08-Access-Bridge の Accessibility Service は画面 OFF 中はタップを受け取れず、wake もできない。**フレームワークより下の層**でタップを捕まえる必要がある。

### 3. 決定的発見：BLE HID はカーネルの wake source

「届かないのはフレームワークのゲートのせいで、カーネルの `/dev/input` には来ているのでは？」と仮説を立て、検証した：

- `adb shell getevent -lt`（画面 OFF）でタップ → `/dev/input/event3` にイベントは**来ている**（フレームワークのゲートより下）。
- さらに **adb を切断し、数分放置した自律ディープサスペンドからでも、単タップで CPU が起きて wake できた**（ログにタップ時刻で記録、画面点灯を目視）。

つまり R08 の HID は BT コントローラ経由でカーネルの wake source になっており、**待機中に AP を完全にサスペンドさせても、タップ時だけ起きる**。これにより：

- 60 秒ごとに画面を一瞬 ON にする keepalive パルスや常時 `PARTIAL_WAKE_LOCK` は**不要**。
- `getevent` のブロッキング read（後述は直 read）は待機中ほぼ 0 CPU。
- ＝ 電池に非常に優しい。

### 4. なぜ `getevent | while read` ではダメで native なのか

最初はシェルで `getevent | while read ...; do ... done` を試したが**動かなかった**。原因は **`getevent` の stdout が（パイプ/ファイルでは）ブロックバッファされる**こと：

- TTY 直（`adb shell getevent ...`）では行バッファ＝ライブで見える。
- パイプ/ファイルに繋ぐと全バッファ＝数十タップぶん溜まるまで `while` に流れてこない（実機で 5 回も 60 回も流れず）。

シェルでは flush を強制できない（Android に `stdbuf` 等は無い）。そこで **evdev を `read(struct input_event)` で直接読む native バイナリ**にした。`read()` はシステムコールなのでバッファリングもパイプも無関係。`input_event` は 64bit で 24 バイト（`timeval`16 + type2 + code2 + value4）。

### 5. wake の撃ち方

タップを検出したら `system("input keyevent 224")`（`KEYCODE_WAKEUP`）でパネルを点ける。`input` も `/dev/input` の read も **shell uid 必須**で、普通のアプリ単体では不可（だから R08-Access-Bridge のような Accessibility アプリではなく、shell で動く native デーモンにしている）。

### 6. ペア iPhone の Apple Music 暴発と `EVIOCGRAB` 抑止

グラスを iPhone 等に BT 接続していると、**タップ wake のたびに Apple Music が再生**される症状が出た。原因：

- グラスは iPhone に対して A2DP/AVRCP の**メディアコントローラ**として振る舞う。
- 画面 OFF 中、`KEY_PLAYPAUSE` は（誰もアプリ側で消費しないので）グラスのメディアセッション経由で **AVRCP の PLAY として iPhone に転送**される → Apple Music が鳴る。

対策は **`ioctl(fd, EVIOCGRAB, 1)`**：呼び出した fd がそのデバイスを**排他確保**し、他の読み手（Android の EventHub/InputReader を含む）にイベントが渡らなくなる。つまり grab 中はメディアキーがフレームワーク＝メディアセッション＝AVRCP に**一切届かない**。実機で `EVIOCGRAB ret=0`（shell uid で成功）＋ タップで Apple Music が**鳴らない**ことを確認。

ただし grab は「そのデバイスの全イベントを独占」するので、**grab しっぱなしだと R08-Access-Bridge の通常ナビ（画面 ON 時のタップ）も死ぬ**。そこで **画面 OFF の間だけ grab、画面 ON では解放**する条件付きにした（下記状態機械）。

### 7. ダブルタップの漏れと「点灯確認まで grab 維持」

初版は「タップで wake したら**即** grab を解放」していた。ところがダブルタップだと：

1. 1 タップ目 → `keyevent 224` → 即解放。
2. しかし画面は点灯しきっておらず、**ungrabbed なのに画面 OFF** の状態に。
3. その後のタップが grab されず、メディア経路へ漏れて Apple Music 再生。

修正：**`keyevent 224` の後、`screen_on()` で実際に点灯したのを確認できた時だけ grab を解放**する。点かなければ grab を維持＝「画面 OFF の間は何タップしても必ず遮断」を保証。これでダブルタップ漏れが消えた（実機確認）。

### デーモンの動作（状態機械）

```
                    ┌─────────────────────── OPEN（画面ON, grab解放）──────────────────────┐
                    │  drain(自分のfd) → poll(2s)                                          │
   起動/再接続 ──▶  │  poll timeout かつ screen_on()==false ─▶ grab + drain ─▶ GRABBED     │
   (screen状態で    │  （タップは Access Bridge が処理）                                    │
    初期grab決定)   └───────────────────────────────▲──────────────────────────────────────┘
                                                     │ screen_on() 確認できたら解放
                    ┌──────────────────── GRABBED（画面OFF, 排他確保）─────────────────────┐
                    │  blocking read（サスペンド可）                                        │
                    │  KEY_PLAYPAUSE down ─▶ keyevent 224                                  │
                    │     └ wait_screen_on(1500ms): 点いた → 解放(OPENへ) / 点かない → 維持 │
                    │  （grab中なので iPhone へ漏れない）                                   │
                    └──────────────────────────────────────────────────────────────────────┘

   read がエラー（ring BLE 切断）─▶ close → 1s sleep → 再 open（screen状態で grab 再判定）
```

ポイント：
- **GRABBED 中は blocking read** なので待機電力ゼロに近い。wake は BT IRQ が AP を起こす。
- **OPEN 中だけ `dumpsys power` を ~2s 間隔でポーリング**（画面 ON＝アクティブ時のみ。OFF→ON 転送は wake 後の `wait_screen_on` が担う）。
- 解放は「点灯確認後」のみ＝**画面 OFF の間に ungrabbed になる瞬間が無い**＝漏れ無し。

### コード構成（`r08waked.c`）

| 関数 | 役割 |
|---|---|
| `open_ring()` | `/dev/input/event*` を走査し、名前に `R08`＋`Consumer` を含むデバイスを `O_RDWR`（失敗時 `O_RDONLY`）で開く。index 非依存 |
| `screen_on()` | `dumpsys power` の `mWakefulness=Awake` を見て画面 ON/OFF 判定 |
| `wait_screen_on(ms)` | `keyevent 224` 後、画面が実際に点くまで 150ms 刻みでポーリング（最大 ms） |
| `set_grab(fd,on)` | `ioctl(fd, EVIOCGRAB, …)` |
| `drain(fd)` | `O_NONBLOCK` にして溜まったイベントを捨てる（grab 前後の残り、ダブルタップ 2 発目など） |
| `main()` | 上記の状態機械。device 切断時は再 open |

定数：`POLL_MS=2000`（OPEN 中の画面ポーリング間隔）、`WAKE_WAIT_MS=1500`（wake 後の点灯待ち上限）。

### ビルドの中身（`build.sh`）

`$ANDROID_SDK_ROOT/ndk` の NDK で `aarch64-linux-android` にクロスコンパイルして `./r08waked` を出す。NDK の clang を自動検出（最も低い API レベルの `aarch64-linux-android*-clang` を選び互換性重視）。出力は bionic 動的リンクの arm64 PIE（`interpreter /system/bin/linker64`）。

---

## 永続化（再起動対応）の詳細＝armapp

`setsid` 起動は adb 切断・自律サスペンドを生き残るが、**reboot では消える**（非 root の Android では起動時に shell uid のプロセスを自動起動できない）。これを **companion も常時 USB も使わずグラス単独**で立て直すのが `armapp/`（`com.hacha.rokidr08wake`）。使い方は上の [A. 実運用](#a-実運用再起動しても復活推奨) を参照。ここでは仕組みとビルドの詳細を書く。

仕組み（実機検証 2026-06-30）：
- 一度だけ shell で **`persist.adb.tcp.port=5555`** を設定 → **再起動後も adbd が `*:5555`（loopback 127.0.0.1:5555 含む）で待受**する（shell で設定可・再起動で残る・実機確認）。
- アプリは **信頼済み adb 鍵を内蔵**（companion が pairing 済みの `adb_keys` の「R08 Companion」鍵を流用＝kadb の `adbkey.pem`）し、**kadb で 127.0.0.1:5555 に loopback 接続**→ adbd が shell uid のシェルを与える → そこで r08waked を deploy＋`setsid` 起動。wifi も外部ホストも不要。
- アプリ起動（`MainActivity.onCreate`）で自動 arm、ボタンでも arm。

### 完全自動（BootReceiver）は ROM がブロック（不可）

`RECEIVE_BOOT_COMPLETED` の受信は、この Rokid ROM が **`Background execution Third-Party APP not allowed`** でサードパーティに対し拒否する（`deviceidle whitelist` / `appops RUN_ANY_IN_BACKGROUND` でも解除不可・実機確認）。よって **ゼロタッチ自動 arm は不可**、「アプリを1回開く」が必要（許容済みの手動1ステップ）。

（リポジトリには boot 時に arm を試みる `BootReceiver` を best-effort で同梱しているが、上記のブロックによりこの ROM では実際には arm されない。よって「アプリを1回開く」は依然必要。）

### 一度だけのセットアップ（USB 一回・以後不要）
1. `adb -s <glasses> shell setprop persist.adb.tcp.port 5555`（再起動で残る／消えたら再設定）
2. 信頼済み adb 鍵をアプリに同梱（`armapp/app/src/main/assets/adbkey.pem`。companion の `files/kadb/adbkey.pem` を `run-as` で抽出したもの）
3. `armapp` をビルドしてグラスに install、一度開く

ビルド（`armapp/`）：kadb が Java21 バイトコードなので **JDK21** が要る。リポジトリに unix `gradlew` は無いので wrapper jar を直叩き（マシンの `~/.gradle/gradle.properties` が JDK17 を指す場合は `-Dorg.gradle.java.home` で上書き）：

```sh
cd armapp   # リポジトリのルートから
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK21 (Android Studio 同梱)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
"$JBR/bin/java" -Dorg.gradle.java.home="$JBR" \
  -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
  :app:assembleDebug --no-daemon --console=plain
# -> app/build/outputs/apk/debug/app-debug.apk
```

同梱物（gitignore 済・別マシンでは要再生成）：`app/src/main/assets/r08waked`（`../build.sh` の成果物）と `app/src/main/assets/adbkey.pem`（信頼済み adb 鍵）。ビルド成果物は `app/build/outputs/apk/debug/app-debug.apk`。

> ⚠️ **ビルド済み APK は絶対に配布しない。** APK には `adbkey.pem`（グラスの adbd が信頼する adb 秘密鍵）が埋め込まれている。グラスの adbd（`*:5555`）に到達できる者がこの鍵を持てば `shell` uid シェルを取得できる。各自ビルドし、APK は非公開に保つこと。

### セキュリティ代償（了承の上）
- adbd が LAN に常時 `*:5555` で待受（key 認証付き）。
- アプリに adb 秘密鍵を内蔵。
- いずれも個人デバイス／自宅 Wi-Fi 前提。

> 旧案（companion 改造で bridge arm 時に r08waked を起動）は、ROM の self-heal 不安定・companion 必須のため**廃止**。fork（`R08-Access-Bridge/`）はナビ用に Anezium 無改変版を使えば不要。ナビ（リング BLE 接続）は引き続き R08-Access-Bridge が提供する＝本 wake はその上で共存。

---

## 既知の制約

- **OFF 転送の隙間**：画面が自動消灯した直後〜約 `POLL_MS`（2s）は grab 確立前で、その瞬間のタップはメディア転送されうる。ただし画面はユーザが操作をやめたから消える＝その間にタップしないので実害はほぼ無い。
- **画面 ON のナビ時**：今回の実機では画面 ON 中のナビでは音楽は鳴らなかった（Accessibility が消費しているとみられる）。もし鳴る環境があれば、**grab 常時＋uinput で「R08」名の仮想デバイスに非メディアキーを再注入**する方式に拡張が必要（メディアキー自体を一切フレームワークに流さない）。現状は「画面 OFF 時だけ抑止」。
- **スワイプもメディアキー**：next/prev もメディアキーなので画面 OFF 中は grab で同様に遮断される（wake には tap だけ使う）。

---

## 関連

- [Anezium/R08-Access-Bridge](https://github.com/Anezium/R08-Access-Bridge) — R08 ring をグラスのナビにする OSS。リングは**グラスに直接 BLE 接続**（スマホ経由ではない）。本ツールはその wake 欠落を補完する。
- `appType` は R08 のリング側に永続する出力モード。`AppType probe` 等で変えると tap/double-tap の挙動が変わるので、おかしくなったら R08-Access-Bridge の **Ring modes → Stable mode** を選び直す（`appType 1` 再送）。

---

## License

[MIT](LICENSE)
