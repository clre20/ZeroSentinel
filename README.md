# ZeroSentinel

ZeroSentinel 是一款專為 Paper (Minecraft 26.3+) 伺服器設計的反作弊插件，目標是「誤判最低、準確度最高」。目前為 **第 0 階段（基礎建設）**：提供封包錄製回放、事件與錯誤碼系統、管理介面，作為之後所有檢查項目的測試與審核基礎；作弊檢查會在後續階段陸續加入。

## 功能特性

* **封包錄製（黑盒子）**：
  * 每位玩家一個固定大小的環形緩衝區（預設 256 KB），隨時保存最近 10 秒的封包，寫入時不配置任何物件。
  * 記錄玩家送給伺服器的所有封包，以及伺服器送給玩家、與判定有關的封包（位置同步、擊退、爆炸、方塊變更、實體、效果、屬性、PING 等）；區塊資料不記錄。
  * 管理員可手動錄製，也會在產生新事件時自動錄製（包含事件前 10 秒與事件後 5 秒）。
  * 錄製檔為 `.zsr.gz`，單檔有容量上限；錄製中寫入 `.part`，當機後下次啟動自動修復。
  * 總容量上限與保留天數自動清理，每次錄製結束都會檢查上限。
* **離線回放工具**：
  * 不需要開伺服器，直接用插件 jar 查看與檢查錄製檔：`info`、`dump`、`test`（回歸測試題庫 `legit/`、`cheat/`）。
* **事件與錯誤碼**：
  * 同一位玩家、同一個分類在 60 秒內的連續違規算同一個事件。
  * 每次發訊息給玩家都附上一組新的錯誤碼（例如 `7KQ2-M9XT4`），完全隨機、與檢查項目無關，玩家無法從中得知任何判定資訊；同一事件可以有很多組錯誤碼。
  * 錯誤碼排除容易看錯的字元，並帶有檢查碼，玩家抄錯時會直接判定為無效。
  * 管理員可用錯誤碼查詢事件，並判定為「合法」或「作弊」，累積誤判統計。
* **箱子 UI 管理介面**：
  * 玩家列表 → 玩家頁（客戶端協議、是否為基岩版、開始 / 停止錄製、最近事件）→ 事件頁（所有錯誤碼、錄製檔、判定按鈕）。
  * 只接受左鍵，Shift、右鍵、拖曳一律無效；介面帶有 `ZeroSentinelHolder` 簽署，方便其他插件辨識。
* **資料庫（SQLite）**：
  * 單一寫入執行緒，批次交易寫入；同一事件的大量違規只佔一筆待寫入（髒資料標記）。
  * 單一讀取執行緒，WAL 模式讀寫互不阻擋；讀取優先使用記憶體快取（LRU）。
  * 過期資料分批清理，不會長時間鎖住資料庫。
* **基岩版辨識**：
  * 安裝 Floodgate 時透過 Floodgate API 辨識基岩版玩家（不使用名稱前綴判斷）。
* **設定檔自動補齊**：
  * `config.yml` 與 `messages.yml` 缺少的項目會在啟動與重載時自動補上，保留原本的值。
  * 所有顯示文字都在 `messages.yml`，支援 `&` 色碼。
* **檢查更新**：
  * 啟動時以非同步方式向 GitHub Releases 檢查更新，結果快取 10 分鐘，不會定時輪詢。
  * 具備 `zerosentinel.version` 權限的管理員登入 3 秒後會收到新版本提醒，兩項功能都可在 `config.yml` 的 `update-checker` 關閉。

## 需求

* Paper 26.3 以上（Purpur 等相容的 Paper 分支也可使用；不支援 Folia）
* Java 25
* [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 以上（必要）
* Floodgate（選用，辨識基岩版玩家）
* SQLite 驅動會由伺服器在第一次啟動時自動下載

## 指令與權限

本插件主指令為 `/zerosentinel`。

| 指令 | 說明 | 執行端 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/zs` | 開啟箱子 UI 管理介面 | 僅限玩家 | `zerosentinel.gui` | OP |
| `/zs help` | 顯示指令說明 | 遊戲內 / 控制台 | `zerosentinel.help` | OP |
| `/zs record <玩家> [秒數]` | 存下最近的封包並繼續錄製 | 遊戲內 / 控制台 | `zerosentinel.record` | OP |
| `/zs record stop <玩家>` | 提前停止錄製並存檔 | 遊戲內 / 控制台 | `zerosentinel.record` | OP |
| `/zs lookup <錯誤碼>` | 查詢錯誤碼對應的事件 | 遊戲內 / 控制台 | `zerosentinel.lookup` | OP |
| `/zs verdict <事件ID或錯誤碼> <legit\|cheat> [備註]` | 判定事件為合法或作弊 | 遊戲內 / 控制台 | `zerosentinel.verdict` | OP |
| `/zs debug flag <玩家>` | 建立測試事件並發警告給玩家（需開啟 `debug.enabled`） | 遊戲內 / 控制台 | `zerosentinel.debug` | OP |
| `/zs reload` | 重新載入 `config.yml` 與 `messages.yml` | 遊戲內 / 控制台 | `zerosentinel.reload` | OP |
| `/zs version` | 檢查更新 | 遊戲內 / 控制台 | `zerosentinel.version` | OP |

> 指令別名：`/zs` 等同 `/zerosentinel`。
>
> `zerosentinel.admin` 與 `zerosentinel.*` 包含以上所有權限。

## 使用範例

### 1. 玩家回報被誤判
1. 玩家提供錯誤碼，例如 `7KQ2-M9XT4`：
   ```bash
   /zs lookup 7KQ2-M9XT4
   ```
2. 確認是誤判後標記為合法：
   ```bash
   /zs verdict 7KQ2-M9XT4 legit 蜂蜜塊邊緣跳躍
   ```

### 2. 錄製可疑玩家
```bash
/zs record Steve 120
```
錄製檔會存在 `plugins/ZeroSentinel/recordings/<日期>/`。

### 3. 用離線工具查看錄製檔
```bash
java -jar ZeroSentinel-1.0.0.jar info recordings/2026-11-16/123045_Steve_manual.zsr.gz
java -jar ZeroSentinel-1.0.0.jar dump recordings/2026-11-16/123045_Steve_manual.zsr.gz 50
java -jar ZeroSentinel-1.0.0.jar test 題庫資料夾
```

## 設定檔

| 欄位 | 說明 | 預設 |
| :--- | :--- | :--- |
| `record.enabled` | 是否錄製 | `true` |
| `record.buffer-kb` | 每位玩家的緩衝區大小（KB） | `256` |
| `record.buffer-seconds` | 開始錄製時往前保留幾秒 | `10` |
| `record.max-packet-kb` | 單一封包超過此大小只記錄 ID 與長度 | `32` |
| `record.on-flag` / `after-flag-seconds` | 新事件自動錄製 / 事件後繼續錄幾秒 | `true` / `5` |
| `record.manual-max-seconds` | 手動錄製最長秒數 | `300` |
| `record.max-session-mb` | 單一錄製檔最大未壓縮容量 | `32` |
| `storage.recordings.max-total-mb` / `retention-days` | 錄製檔總容量上限 / 保留天數 | `2048` / `30` |
| `storage.database.flush-seconds` / `batch-size` | 資料庫批次寫入間隔 / 累積筆數 | `5` / `500` |
| `storage.database.cache-size` / `retention-days` | 記憶體快取事件數 / 保留天數 | `2000` / `30` |
| `events.merge-window-seconds` | 合併為同一事件的時間 | `60` |
| `gui.record-seconds` | 介面「開始錄製」的秒數 | `60` |
| `display.time-format` | 時間顯示格式 | `yyyy-MM-dd HH:mm:ss` |
| `debug.enabled` | 開啟 `/zs debug` 測試指令 | `false` |
| `update-checker.check-on-startup` / `notify-on-join` | 啟動檢查更新 / 管理員登入提醒 | `true` / `true` |


# ZeroSentinel

ZeroSentinel is an anti-cheat plugin for Paper (Minecraft 26.3+) servers, built for the lowest possible false-positive rate and the highest accuracy. This is **Stage 0 (infrastructure)**: packet recording and replay, an event and error-code system, and a management GUI that every future check will be tested and reviewed against. Cheat checks will be added in later stages.

## Features

* **Packet recording (black box)**:
  * A fixed-size ring buffer per player (256 KB by default) keeps the last 10 seconds of packets with zero allocation on write.
  * Records every packet the player sends and the server-sent packets relevant to detection (position sync, knockback, explosions, block changes, entities, effects, attributes, PING, …); chunk data is excluded.
  * Recordings can be started manually and start automatically on new events (10 seconds before, 5 seconds after).
  * Files are `.zsr.gz` with a per-file size cap; in-progress files are written as `.part` and repaired on the next start after a crash.
  * Total size cap and retention are enforced automatically, checked after every recording.
* **Offline replay tool**:
  * Inspect and verify recordings with the plugin jar, no server needed: `info`, `dump`, `test` (regression suites `legit/` and `cheat/`).
* **Events & error codes**:
  * Consecutive violations by the same player in the same category within 60 seconds form one event.
  * Every message sent to a player carries a fresh error code (e.g. `7KQ2-M9XT4`) that is fully random and unrelated to the check, so players learn nothing about detection; one event can have many codes.
  * Codes avoid easily confused characters and include a check digit, so mistyped codes are rejected.
  * Staff look up events by code and mark them legit or cheat, building false-positive statistics.
* **Chest GUI**:
  * Player list → player page (client protocol, Bedrock flag, start/stop recording, recent events) → event page (all error codes, recording file, verdict buttons).
  * Left-click only; Shift, right-click, and drag do nothing. Inventories carry a `ZeroSentinelHolder` signature for other plugins to identify.
* **Database (SQLite)**:
  * One writer thread with batched transactions; repeated violations of one event take a single pending write (dirty flag).
  * One reader thread; WAL mode keeps reads and writes from blocking each other; reads hit an in-memory LRU cache first.
  * Expired data is deleted in small batches to avoid long locks.
* **Bedrock detection**:
  * Uses the Floodgate API when installed (never relies on name prefixes).
* **Auto-completing config files**:
  * Missing keys in `config.yml` and `messages.yml` are added on startup and reload while keeping existing values.
  * All displayed text lives in `messages.yml` with `&` color code support.
* **Update checker**:
  * Checks GitHub Releases asynchronously on startup; results are cached for 10 minutes with no polling.
  * Admins with `zerosentinel.version` are notified 3 seconds after joining; both features can be disabled under `update-checker`.

## Requirements

* Paper 26.3+ (compatible Paper forks such as Purpur also work; Folia is not supported)
* Java 25
* [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0+ (required)
* Floodgate (optional, for Bedrock detection)
* The SQLite driver is downloaded automatically by the server on first start

## Commands & Permissions

The primary command for this plugin is `/zerosentinel`.

| Command | Description | Executor | Permission Node | Default |
| :--- | :--- | :--- | :--- | :--- |
| `/zs` | Open the chest GUI | Player only | `zerosentinel.gui` | OP |
| `/zs help` | Show command help | In-game / Console | `zerosentinel.help` | OP |
| `/zs record <player> [seconds]` | Save recent packets and keep recording | In-game / Console | `zerosentinel.record` | OP |
| `/zs record stop <player>` | Stop recording early and save | In-game / Console | `zerosentinel.record` | OP |
| `/zs lookup <code>` | Look up the event behind an error code | In-game / Console | `zerosentinel.lookup` | OP |
| `/zs verdict <eventId\|code> <legit\|cheat> [note]` | Mark an event legit or cheat | In-game / Console | `zerosentinel.verdict` | OP |
| `/zs debug flag <player>` | Create a test event and warn the player (requires `debug.enabled`) | In-game / Console | `zerosentinel.debug` | OP |
| `/zs reload` | Reload `config.yml` and `messages.yml` | In-game / Console | `zerosentinel.reload` | OP |
| `/zs version` | Check for updates | In-game / Console | `zerosentinel.version` | OP |

> Alias: `/zs` is the same as `/zerosentinel`.
>
> `zerosentinel.admin` and `zerosentinel.*` include every permission above.
