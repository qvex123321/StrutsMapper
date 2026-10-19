# StrutsMapper 使用說明

掃描 Struts 1 專案，產生一個可以離線瀏覽的 HTML 對應表：URL → Action 方法 → 呼叫的資料庫程序（SP）→ 轉向的 JSP，並可反查「這支 SP 被哪些畫面用到」。

## 檔案

| 檔案 | 用途 |
|---|---|
| `StrutsMapper.java` | 解析工具。Java 8 以上即可執行，不需要任何外部 jar。原始碼刻意只用英文字元，不受 Eclipse 編碼設定影響。 |
| `viewer-template.html` | 頁面範本，必須和 `StrutsMapper.java` 放在同一個資料夾。 |

## 在 Eclipse 執行

1. **File → New → Java Project**，名稱自訂（例如 `struts-mapper`），JRE 選 Java 8 以上。
2. 把 `StrutsMapper.java` 和 `viewer-template.html` 複製到這個專案的 `src` 底下，**直接放在 src，不要建 package**。
3. 對 `StrutsMapper.java` 按右鍵 → **Run As → Run Configurations…** → 左側 Java Application 新增一組。
4. **Arguments** 分頁的 Program arguments 填入：
   ```
   "D:\workspace\你的專案" "D:\struts-map\struts-map.html"
   ```
   第一個是要掃描的專案根目錄，第二個是輸出位置（可省略，預設輸出到工作目錄的 `struts-map.html`）。
5. 按 **Run**。Console 出現 `Done` 後，用 Chrome 或 Edge 開啟產生的 HTML。

專案很大時，可在同一個視窗的 **VM arguments** 加上 `-Xmx1g`。

## 輸出

- `struts-map.html`：單一檔案，資料已內嵌，不需要網路或伺服器，雙擊即可開啟，也可以直接轉寄給別人。
- `struts-map.json`：同一份資料，之後想匯入 SQLite 或做其他分析時使用。

程式碼有更新時，重新執行一次即可。

## 頁面怎麼用

- 按 `/` 跳到搜尋框。可以搜 URL、類別、方法、SP 名稱、JSP 路徑、資料表名稱，多個關鍵字用空白分開。
- 左側分頁切換 Action、SP、JSP、警告清單。
- Action 頁面上方的流程圖：滑鼠移到某個方法，只會亮出它的路徑；點方法會捲到下方的詳細卡片。
- 檔名和行號（例如 `PremiumDao.java:12`）點一下會複製完整路徑和行號。到 Eclipse 用 `Ctrl+Shift+R` 輸入檔名開啟，再用 `Ctrl+L` 跳到該行。
- 呼叫路徑（例如 `QuoteAction.calc → PremiumServiceImpl.calculate → PremiumDao.callCalc`）的每一段也可以點擊複製位置。

## 分析的內容

- `web.xml`：ActionServlet 載入的設定檔與模組、URL 規則、Filter、Listener。
- `struts-config*.xml`：action、form-bean、forward、global-forwards、例外處理、Tiles 與 Validator 外掛。
- Tiles 定義、`validation.xml`、Spring 的 bean 設定（處理 `DelegatingActionProxy`）。
- 訊息檔（`ApplicationResources*.properties`），用來顯示 LookupDispatchAction 的按鈕文字。
- Java 原始碼：Action 的分派方式與方法、`findForward` 用到的名稱、session / request 屬性，並沿著方法呼叫往下追，找出最終呼叫的 SP 與 SQL 資料表。
- JSP：表單和連結呼叫哪些 Action、include 關係、scriptlet 中直接寫的 SQL 與 SP。

## 警告會抓出的問題

- 程式中 `findForward("xxx")` 的名稱在設定檔找不到（執行時會回傳 null）
- forward 指向的 JSP 或 Tiles 不存在
- struts-config 指定的 Action 類別找不到原始碼
- 沒有在 web.xml 註冊的 struts-config（可能是遺留檔案）
- SP 名稱是字串拼接出來的，需要人工確認

## 限制

這是靜態分析，結果適合當作盤點的起點，關鍵流程仍建議實際除錯確認。

- 方法呼叫是依變數、欄位、方法回傳值的**宣告型別**追蹤。介面會展開到所有實作類別；同一個方法被超過 8 個子類別覆寫時不展開，避免誤連。
- 同名的多載方法不區分參數。
- 反射、用 Map 或設定檔決定的呼叫、`getBean("名稱")` 後沒有轉型的寫法，無法追蹤。
- SP 是從字串辨識：`{call ...}`、`{? = call ...}`、`BEGIN ... END;`、以及 SQL 中的 `PKG.FUNCTION(...)`。透過常數組合的字串會一併展開；執行期才決定的部分會標示為動態。
- 只支援 Struts 1，不支援 Struts 2。

## 選項

在 VM arguments 加上：

| 參數 | 說明 |
|---|---|
| `-Dsm.encoding=MS950` | 檔案不是合法 UTF-8 時改用的編碼，預設為 MS950（Big5）。 |
| `-Dsm.skip=資料夾1,資料夾2` | 額外略過的資料夾名稱。預設已略過 `bin`、`classes`、`target`、`.svn`、`.git` 等，避免重複掃到編譯產物。 |
