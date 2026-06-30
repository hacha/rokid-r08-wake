# R08Wake

R08 スマートリングの **単発タップで Rokid Glasses をスリープ復帰**させる常駐ヘルパー（shell uid / native）。

リングのナビ操作そのものは [Anezium/R08-Access-Bridge](https://github.com/Anezium/R08-Access-Bridge)（リング→グラス直 BLE）が担う。本ツールはそこに無い「画面 OFF からの単タップ wake」だけを補う。

## 原理（実機確認 2026-06-30）

- R08 の単タップ = `KEY_PLAYPAUSE` が `/dev/input/event3`（`"R08_4B00 Consumer Control"`）に届く。
- Rokid Glasses ではこの **BLE HID がカーネルの wake source**＝adb 切断・自律ディープサスペンドでもタップで CPU が起きる。
- `r08waked` は evdev を直接 `read(struct input_event)` し、`KEY_PLAYPAUSE` の down かつ画面 OFF（`dumpsys power` の `mWakefulness != Awake`）のとき `input keyevent 224`（`KEYCODE_WAKEUP`）を撃つ。
- **keepalive / wakelock 不要**：blocking read は待機中ほぼ 0 CPU、AP は普通にサスペンドでき、タップ時だけ BT IRQ が起こす＝電池に優しい。
- **メディアキー抑止**：リングの `KEY_PLAYPAUSE` は、グラスとペアした iPhone 等へメディアセッション(AVRCP)経由で転送され、タップ wake のたびに **Apple Music が再生**されてしまう。対策として **画面OFF の間だけ `EVIOCGRAB` でデバイスを排他確保**し、タップをメディア経路に流さず wake だけ行う。画面ON では grab を解放し Access Bridge がナビ処理（`EVIOCGRAB ret=0` / 実機で Apple Music 非再生を確認 2026-06-30）。

### なぜ native か

`getevent | while read` は getevent の stdout が（パイプ/ファイルでは）ブロックバッファされ、シェルの while にイベントが流れない（TTY 直なら live で出る）。よって evdev を直 read する native binary が必要。`/dev/input` の read も `input` の実行も **shell uid 必須**（普通のアプリ単体では不可）。

## ビルド

```sh
./build.sh   # $ANDROID_SDK_ROOT/ndk の NDK で aarch64 にクロスコンパイル → ./r08waked
```

## グラスへ展開 / 起動

```sh
./arm-r08wake.sh   # push → setsid 起動 → ログ＆生存確認
```

`setsid` 起動なので **adb 切断・自律サスペンドは生き残る**。ただし **reboot 後は再実行が必要**（Android の制約で起動時に shell uid を自動起動できない）。完全自動にしたい場合は R08-Access-Bridge の武装済み shell bridge 起動シーケンスに相乗りさせる（将来案）。

## 動作確認

```sh
GL=$(adb devices | awk 'NR>1&&$2=="device"{print $1}' | while read s; do \
  [ "$(adb -s $s shell getprop ro.product.model|tr -d '\r')" = RG-glasses ] && echo $s; done)
adb -s "$GL" shell input keyevent 223          # 画面OFF
# リングを1タップ → 画面が点く
adb -s "$GL" shell cat /data/local/tmp/r08waked.log   # "tap while off -> WAKEUP"
```

長時間放置後はリング側 BLE が切れていることがあり、最初のタップは再接続で消費され 2 回目で点く場合がある（ヘルパーは自動で開き直す）。

## 停止

```sh
adb -s "$GL" shell pkill r08waked
```

## 既知の制約

- 画面OFF 直後〜数秒（`POLL_MS`≈2s）は grab 確立前の隙間があり、その瞬間のタップはメディア転送されうる。画面はタイムアウト＝ユーザが操作をやめたから消える、ので実害はほぼ無い。
- 画面ON でのナビ操作時にも Apple Music が鳴る場合は、accessibility が消費できていない＝grab 常時＋非メディアキーの再注入（uinput で R08 名の仮想デバイス）に拡張が必要。現状は「画面OFF時だけ抑止」。
