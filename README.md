# ZeroSentinel

ZeroSentinel 是一款專為 Paper (Minecraft 26.3+) 伺服器設計的反作弊插件，目標是「誤判最低、準確度最高」。目前提供封包錄製回放、事件與錯誤碼系統、信用點數、分級處罰，以及第一個檢查項目「客戶端回報異常」；其餘作弊檢查會在後續版本陸續加入。

## 功能特性

* **客戶端回報異常 / 客戶端無回應**：
  * 正常的 Java 版客戶端（1.9～26.x）不論是否在掛機，每秒都會送出移動或 tick 結束封包。
  * 超過 10 秒沒有、但**同時還在聊天、下指令、點擊或攻擊**：判定為異常（機器人或凍結位置的外掛），走完整處罰流程。
  * 超過 60 秒沒有、**也沒有任何操作**：判定為客戶端卡住，不扣信用、不警告，直接請玩家重新進入。
  * 進服、切換世界、重生後 15 秒內，死亡期間，以及伺服器整體停頓後 5 秒內都不檢查；基岩版預設不檢查。
  * 已用 50 多份真實錄製回放驗證，正常玩家 0 誤判。
* **信用點數與分級處罰**：
  * 每位玩家一個信用點數（預設 500 / 1000），玩家可用 `/zs trust` 查看自己的分數。
  * 違規時警告並扣分，累積到門檻就踢出（附原因與錯誤碼），踢出後 30 分鐘內再犯會直接踢出。
  * 信用越高，需要越多次違規才會被踢；連續在線沒有違規會慢慢加分（每日有上限）。
  * 分數過低時無法進服（不是封鎖），提示玩家開客服單；管理員可隨時調整分數，所有修改都有紀錄。
  * 管理員把事件判定為合法（誤判）時，自動退還該事件扣掉的分數；可一次判定某位玩家的所有事件。
  * 伺服器 TPS 過低時暫停處罰，只記錄並通知管理員。
* **伺服器停頓偵測**：
  * 獨立的看門狗執行緒偵測整個伺服器的停頓（例如 GC），停頓後的寬限時間內不累積違規，並在後台記錄停頓長度。
* **版本統計**：
  * `/zs versions` 列出線上玩家使用的各個 Java 版本與人數，以及基岩版人數。
* **封包錄製（黑盒子）**：
  * 每位玩家一個固定大小的環形緩衝區（預設 256 KB），隨時保存最近 10 秒的封包，寫入時不配置任何物件。
  * 記錄玩家送給伺服器的所有封包，以及伺服器送給玩家、與判定有關的封包（位置同步、玩家自己的擊退、爆炸、方塊變更、實體、效果、屬性、PING 等）；區塊資料與其他實體的速度不記錄。
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
| `/zs record <玩家\|all> [秒數]` | 存下最近的封包並繼續錄製（`all` = 所有線上玩家） | 遊戲內 / 控制台 | `zerosentinel.record` | OP |
| `/zs record stop <玩家\|all>` | 提前停止錄製並存檔 | 遊戲內 / 控制台 | `zerosentinel.record` | OP |
| `/zs lookup <錯誤碼>` | 查詢錯誤碼對應的事件 | 遊戲內 / 控制台 | `zerosentinel.lookup` | OP |
| `/zs verdict <事件ID或錯誤碼> <legit\|cheat> [備註]` | 判定事件為合法或作弊（合法時自動退還扣分） | 遊戲內 / 控制台 | `zerosentinel.verdict` | OP |
| `/zs verdict player <玩家> <legit\|cheat> [備註]` | 一次判定該玩家所有未判定的事件（可離線） | 遊戲內 / 控制台 | `zerosentinel.verdict` | OP |
| `/zs versions` | 統計線上玩家使用的版本 | 遊戲內 / 控制台 | `zerosentinel.versions` | OP |
| `/zs trust` | 查看自己的信用點數 | 僅限玩家 | `zerosentinel.trust` | 所有人 |
| `/zs trust <玩家>` | 查看玩家的信用點數（可離線） | 遊戲內 / 控制台 | `zerosentinel.trust.admin` | OP |
| `/zs trust <玩家> <set\|add\|take> <數值> [原因]` | 修改玩家的信用點數（可離線，會留下紀錄） | 遊戲內 / 控制台 | `zerosentinel.trust.admin` | OP |
| `/zs debug flag <玩家>` | 建立測試事件並發警告給玩家（需開啟 `debug.enabled`） | 遊戲內 / 控制台 | `zerosentinel.debug` | OP |
| `/zs debug violation <玩家>` | 模擬一次「客戶端回報異常」違規，走完整處罰流程（需開啟 `debug.enabled`） | 遊戲內 / 控制台 | `zerosentinel.debug` | OP |
| — | 接收違規、踢出、拒絕進服的通知 | — | `zerosentinel.alerts` | OP |
| `/zs reload` | 重新載入 `config.yml` 與 `messages.yml` | 遊戲內 / 控制台 | `zerosentinel.reload` | OP |
| `/zs version` | 檢查更新 | 遊戲內 / 控制台 | `zerosentinel.version` | OP |

> 指令別名：`/zs` 等同 `/zerosentinel`。
>
> `zerosentinel.admin` 與 `zerosentinel.*` 包含以上所有權限。`zerosentinel.trust`（查看自己的分數）預設所有玩家都有。

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
java -jar ZeroSentinel-1.1.1.jar info recordings/2026-11-16/123045_Steve_manual.zsr.gz
java -jar ZeroSentinel-1.1.1.jar dump recordings/2026-11-16/123045_Steve_manual.zsr.gz 50
java -jar ZeroSentinel-1.1.1.jar test 題庫資料夾
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
| `checks.join-grace-seconds` | 進服、切換世界、重生後不檢查的秒數 | `15` |
| `checks.client-report.silence-seconds` / `interval-seconds` | 幾秒沒回報算違規 / 持續時每隔幾秒再算 | `10` / `10` |
| `checks.client-report.kick-after` | 正常信用下累積幾次踢出 | `6` |
| `checks.client-report.repeat-kick-minutes` | 踢出後幾分鐘內再犯直接踢 | `30` |
| `checks.client-report.trust-per-violation` / `trust-per-kick` | 每次違規 / 踢出扣的信用 | `5` / `30` |
| `checks.client-frozen.enabled` / `kick-after-seconds` | 客戶端卡住時踢出（不扣信用） / 幾秒後踢出 | `true` / `60` |
| `trust.start` / `max` | 初始分數 / 上限 | `500` / `1000` |
| `trust.deny-join-at` | 分數小於等於此值無法進服（-1 關閉） | `0` |
| `trust.gain.amount` / `every-minutes` / `daily-cap` | 在線無違規加分 | `2` / `30` / `20` |
| `trust.high.*` / `trust.low.*` | 高 / 低信用的門檻與倍率 | `800 ×2` / `300 ×0.5` |
| `lag.stall-threshold-ms` / `grace-seconds` | 停頓判定 / 停頓後寬限 | `1000` / `5` |
| `lag.min-tps` | TPS 低於此值暫停處罰 | `18.0` |
| `debug.enabled` | 開啟 `/zs debug` 測試指令 | `false` |
| `update-checker.check-on-startup` / `notify-on-join` | 啟動檢查更新 / 管理員登入提醒 | `true` / `true` |


# ZeroSentinel

ZeroSentinel is an anti-cheat plugin for Paper (Minecraft 26.3+) servers, built for the lowest possible false-positive rate and the highest accuracy. It currently provides packet recording and replay, an event and error-code system, trust scores, tiered punishments, and the first check, "abnormal client reporting"; more checks will follow.

## Features

* **Abnormal client reporting / unresponsive client**:
  * A normal Java client (1.9–26.x) sends movement or tick-end packets every second, even while AFK.
  * More than 10 seconds without them **while still chatting, running commands, clicking or attacking** is flagged as abnormal (bots, position-freezing hacks) and goes through the full punishment flow.
  * More than 60 seconds without them **and no actions at all** is treated as a frozen client: no trust loss, no warnings, the player is asked to rejoin.
  * Skipped for 15 seconds after join, world change and respawn, while dead, and for 5 seconds after a whole-server stall; Bedrock is skipped by default.
  * Replayed against 50+ real recordings with zero false positives.
* **Trust score & tiered punishments**:
  * Every player has a trust score (500 / 1000 by default); players can check theirs with `/zs trust`.
  * Violations warn and deduct points; reaching the threshold kicks with a reason and error code, and re-offending within 30 minutes of a kick kicks immediately.
  * Higher trust needs more violations before a kick; clean playtime slowly restores points (with a daily cap).
  * Players at or below the minimum cannot join (not a ban) and are told to open a support ticket; staff can adjust scores anytime and every change is logged.
  * Marking an event legit (false positive) automatically refunds the points it deducted; all of a player's events can be judged at once.
  * Punishments pause when TPS is low; violations are only logged and reported to staff.
* **Server stall detection**:
  * A dedicated watchdog thread detects whole-server stalls (e.g. GC) and ignores violations during the grace period afterwards.
* **Version statistics**:
  * `/zs versions` lists online players by Java version plus the Bedrock count.
* **Packet recording (black box)**:
  * A fixed-size ring buffer per player (256 KB by default) keeps the last 10 seconds of packets with zero allocation on write.
  * Records every packet the player sends and the server-sent packets relevant to detection (position sync, the player's own knockback, explosions, block changes, entities, effects, attributes, PING, …); chunk data and other entities' velocity are excluded.
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
| `/zs record <player\|all> [seconds]` | Save recent packets and keep recording (`all` = every online player) | In-game / Console | `zerosentinel.record` | OP |
| `/zs record stop <player\|all>` | Stop recording early and save | In-game / Console | `zerosentinel.record` | OP |
| `/zs lookup <code>` | Look up the event behind an error code | In-game / Console | `zerosentinel.lookup` | OP |
| `/zs verdict <eventId\|code> <legit\|cheat> [note]` | Mark an event legit or cheat (legit refunds deducted points) | In-game / Console | `zerosentinel.verdict` | OP |
| `/zs verdict player <player> <legit\|cheat> [note]` | Judge all of a player's unjudged events at once (offline OK) | In-game / Console | `zerosentinel.verdict` | OP |
| `/zs versions` | Count online players by version | In-game / Console | `zerosentinel.versions` | OP |
| `/zs trust` | Show your own trust score | Player only | `zerosentinel.trust` | Everyone |
| `/zs trust <player>` | Show a player's trust score (offline OK) | In-game / Console | `zerosentinel.trust.admin` | OP |
| `/zs trust <player> <set\|add\|take> <value> [reason]` | Change a player's trust score (offline OK, logged) | In-game / Console | `zerosentinel.trust.admin` | OP |
| `/zs debug flag <player>` | Create a test event and warn the player (requires `debug.enabled`) | In-game / Console | `zerosentinel.debug` | OP |
| `/zs debug violation <player>` | Simulate one abnormal-reporting violation through the full punishment flow (requires `debug.enabled`) | In-game / Console | `zerosentinel.debug` | OP |
| — | Receive violation, kick and join-denial alerts | — | `zerosentinel.alerts` | OP |
| `/zs reload` | Reload `config.yml` and `messages.yml` | In-game / Console | `zerosentinel.reload` | OP |
| `/zs version` | Check for updates | In-game / Console | `zerosentinel.version` | OP |

> Alias: `/zs` is the same as `/zerosentinel`.
>
> `zerosentinel.admin` and `zerosentinel.*` include every permission above.
