# PLAN — Hoàn thiện vai trò STAFF (Review tier) của LivingDocs

> **Mục đích tài liệu:** đây là prompt/kế hoạch làm việc **độc lập**, dùng để mở một chat mới và giao
> cho agent thực hiện. Copy nguyên văn phần "PROMPT CHO CHAT MỚI" ở cuối file là chạy được.
>
> **Trạng thái:** chưa sửa gì. Repo ở commit `7e105cd`, branch `XuanDu`, working tree sạch.
> **Lượng đánh giá:** dựa trên rà soát toàn bộ `backend/src/main/java/com/livingdocs/modules/review`,
> `modules/version`, `modules/document`, `modules/drift`, `modules/template`, `common/security`,
> `db/migration/V3-V39` và frontend `ReviewQueuePage.tsx`, `DocumentDetailPage.tsx`,
> `services/drift.ts`, `services/documents.ts`.

---

## 0. Bối cảnh hệ thống (cần nắm trước khi sửa)

### 0.1 Mô hình 2 trục quyền hạn

LivingDocs dùng **2 trục độc lập**, enforcement là **AND**:

| Trục | Nguồn | Giá trị | Ý nghĩa |
|---|---|---|---|
| Platform role | `user_roles` → `roles.code` | `DEVELOPER`, `STAFF`, `TECHNICAL_LEAD`, `MANAGER`, `ADMIN` | "Bậc nào?" — do Admin cấp |
| Workspace role | `workspace_members.role` | `MEMBER`, `MANAGER` | "Có quyền trong workspace này không?" |

Ngoài ra còn có **permission matrix** (`permissions` + `role_permissions`), resolve qua
`PermissionService.effectivePermissions(userId)` (cached `user-permissions`), enforce qua
annotation `@RequirePermission` + `PermissionInterceptor`.

**Quy tắc bất di bất dịch của repo:** *đã có trong hệ thống thì phải dùng permission matrix,
không hardcode platform role* — xem comment đầu file `V36__capability_permissions.sql` giải thích
vì sao matrix "trang trí" là bug.

### 0.2 Tác nhân theo vai trò (theo yêu cầu)

- **DEVELOPER** — tác giả: tạo/sửa tài liệu, regenerate, dismiss draft, rollback, toggle auto-update,
  chạy drift scan, submit draft để review.
- **STAFF** = **DEVELOPER + review tier 1** (spec: "role này sẽ có thêm các tính năng của role developer").
  Trách nhiệm: review tier-1, approve → forward lên Manager, reject → trả về Developer.
- **MANAGER** — approve tier-2 + publish.

### 0.3 Vòng đời `document_versions.status`

```
DRAFT ──submit-for-review──▶ PENDING ──Staff APPROVED──▶ IN_REVIEW ──Manager APPROVED──▶ APPROVED ──publish──▶ PUBLISHED
                                                     │                                        │
                                                     └──Staff REJECTED/REQUEST_CHANGES──┐     └──(version cũ)──▶ SUPERSEDED
                                                                                          ▼
                                                                                       REJECTED
```

- `DRAFT` — mới tạo, **chưa** ai xem.
- `PENDING` — trong queue Staff.
- `IN_REVIEW` — Staff đã duyệt, chờ Manager.
- `APPROVED` — Manager duyệt, chờ publish.
- `PUBLISHED` — head của document.
- `REJECTED` — bị trả lại / dismiss.
- `SUPERSEDED` — đã bị version mới thay.

`documents.status` mirror theo: `DRAFT / IN_REVIEW / APPROVED / PUBLISHED / REJECTED / ARCHIVED`.

### 0.4 Hai loại "review" trong hệ thống — đừng lẫn

| Loại | Bảng | Người quyết định | Mục đích |
|---|---|---|---|
| **Version review** (cần sửa) | `document_reviews` | Staff → Manager | Duyệt nội dung một `document_version` |
| **Doc update request** | `doc_update_requests` | bất kỳ member → Manager push PR | Duyệt đề xuất *push file lên GitHub* |

Hai hệ thống này **độc lập nhau**, dùng chung `ReviewDecision`/`DocUpdateStatus` khác nhau.

---

## 1. Kết quả đánh giá hiện tại (13 yêu cầu của role STAFF)

### 1.1 Đã có đầy đủ ✅

| # | Yêu cầu | Hiện thực |
|---|---|---|
| 1 | Xem queue review | `DocumentReviewService.staffQueue()` (L351), `itemsForWorkspaceStatus()` (L386), `GET /workspaces/{id}/reviews/staff-queue`, UI tab "Staff review" (`ReviewQueuePage.tsx:146`) |
| 3 | Change log của tài liệu | `GET /documents/{id}/versions` → `DocumentVersionSummary`; phân biệt AI vs human qua `actorRole` enum (`AI/STAFF/DEVELOPER/MANAGER/SYSTEM`) |
| 4 | So sánh với bản approved cuối | `DocumentVersionService.diffAgainstPublished()` (L411), `GET /documents/{id}/diff?comparedTo=published`, UI nút "AI vs Published" |
| 11 | Approve → forward lên Manager | `submitStaffReview()` + `applyStaffVerdict()` (L444-449): `version→IN_REVIEW`, `document→IN_REVIEW`; Manager queue lấy `IN_REVIEW` (L223) |
| 12 | Reject → trả về author kèm feedback | `REJECTED`/`REQUEST_CHANGES` → `version→REJECTED`, `document→REJECTED`; comment lưu `document_reviews.comment`; author thấy qua "My submissions" (`stageOf()` L319) + notification `review.decided` |
| 13 | Lịch sử review + trạng thái | `GET /documents/{did}/versions/{vid}/reviews`, `GET /me/reviews`, tab "My submissions" hiện stage/decision/comment/tên reviewer |

### 1.2 Có phần ⚠️

| # | Yêu cầu | Thiếu gì |
|---|---|---|
| 2 | Grounding evidence | Có source file / code entity / commit / diff ✅. **Thiếu ticket (Jira issue)** — không có cột nào trong `document_versions` hay `document_reviews` lưu ticket reference. Cũng thiếu cột `repository_id` trên `document_versions` để tra ticket theo repo |
| 5 | Verify template structure | ❌ **Chưa có kiểm tra tự động.** `TemplateSchema.validate()` chỉ validate *JSON schema của template definition* lúc admin tạo template — **không** so nội dung tài liệu với template. Không có endpoint "doc này có đủ section của template không" |
| 6 | Verify nhất quán với source code | ⚠️ Chỉ gián tiếp qua `drift_alerts` (`DriftAlertService`) + `code_document_links`. Không có "consistency check" tại thời điểm review; review queue không hiển thị drift severity |
| 7 | Edit sửa lỗi nhỏ trong lúc review | ⚠️ Chỉ edit được ở `DocumentDetailPage` (textarea auto-save tạo version mới `DRAFT`). **Không có inline edit trong review screen**, và version sửa tạo ra là `DRAFT` chứ không phải version đang review → không thay thế được bản đang review |
| 10 | Change log chi tiết trong lúc review | ⚠️ `ReviewQueuePage` chỉ hiện `changeSummary` 1 dòng (truncate `18rem`). Muốn xem timeline đầy đủ phải nhảy `DocumentDetailPage` |

### 1.3 Thiếu hẳn ❌

| # | Yêu cầu | Ghi chú |
|---|---|---|
| 8 | Comment/request changes **trên section cụ thể** | `document_reviews` chỉ có `comment TEXT` không gắn section. `SubmitReviewRequest` chỉ `{decision, comment max4000}`. Không có khái niệm section/anchor/markdown-heading target |
| 9 | Filter & sort queue (repository / template / drift severity / submission date) | `ReviewQueueItemResponse` không có `repositoryId`, `templateId`, `severity`, `submittedAt`. Backend load **toàn bộ document của workspace** rồi filter in-memory (L386-410) → O(docs × versions). Frontend không có control filter/sort nào |

### 1.4 Logic sai cần sửa 🔴 (chi tiết ở Phần 3)

| ID | Vấn đề |
|---|---|
| **B1** | `requireStaffReviewer`/`requireManagerApprover` hardcode platform role, **không** dùng `DOCUMENT_VERSION_REVIEW`/`DOCUMENT_VERSION_APPROVE`/`DOCUMENT_VERSION_PUBLISH` → admin tắt permission trong matrix thì vẫn review/publish được |
| **B2** | Không chặn **self-review**: Staff submit draft của chính mình rồi tự approve → tự forward lên Manager |
| **B3** | `document.setStatus()` bị ghi đè bởi version cũ hơn — không kiểm tra version có còn là bản mới nhất |
| **B4** | `markPendingForStaffReview()` là **dead code** — AI-generated doc **không tự vào queue**, phải tác giả bấm nút (trái spec "queue of AI-generated and auto-updated documentation pending review") |
| **B5** | `dismissVersion`: STAFF có `DOCUMENT_VERSION_DISMISS` từ V37 nhưng logic `canApprove` chỉ gồm `TECHNICAL_LEAD/MANAGER/ADMIN` → **Staff không dismiss được draft của người khác**; ngược lại MANAGER dismiss được draft người khác (lệch mong muốn "STAFF = DEVELOPER + review") |
| **B6** | `rollback()` tạo version `DRAFT` nhưng **không set `document.headVersionId`/`status`** → head trỏ bản cũ, UI hiển thị sai |
| **B7** | Manager approve version PENDING nếu có bất kỳ Staff review APPROVED nào trong lịch sử — không check verdict **mới nhất**, không check version đang `IN_REVIEW` |
| **B8** | Thiếu unique constraint trên `document_reviews` (comment trong `V3` nói có partial unique index nhưng **không hề tồn tại**) → race condition 2 Staff review cùng lúc |
| **B9** | `REVIEW_ASSIGNED` notification kind tồn tại nhưng **không được emit ở đâu**; `reviewersFor()` gửi `review.requested` cho **mọi** member có `canStaffReview()` (gồm MANAGER/ADMIN) — không có khái niệm assignment |
| **B10** | `DocumentReviewController` nhận `{workspaceId}` trong path nhưng **không dùng** — `loadAndAuthorize()` chỉ check membership theo `document.workspaceId` |
| **B11** | `DocumentService.toggleAutoUpdate()` chỉ check `requireMember`, **không** dùng `REPOSITORY_AUTO_UPDATE_MANAGE` → mâu thuẫn với V37 |
| **B12** | `DocumentService.update()` — `update()` check owner hoặc workspace MANAGER nhưng **không** dùng `DOCUMENT_UPDATE` permission |
| **B13** | `DocumentVersionRepository.findAllBySubmittedByUserIdAndDocumentIdInAndStatusInOrderBySubmittedAtDesc()` (L46) là **dead code** — định nghĩa nhưng không nơi nào gọi (chỉ `mySubmissions` dùng biến thể không filter status) |
| **B14** | `ReviewQueueItemResponse.createdAt` = `version.createdAt` (lúc version được tạo), **không** phải `submittedAt` (lúc submit) → sort/filter "theo ngày submit" sẽ sai. Cột `submitted_at` đã có sẵn từ V39 nhưng DTO không expose |

---

## 2. Chiến lược thực hiện

Theo thứ tự: **nhóm S (logic sai) → nhóm M (tính năng thiếu) → nhóm L (UI polish)**.
Mỗi nhóm là 1 commit riêng, build + test phải pass trước khi sang nhóm sau.

### Quy ước bắt buộc

1. **Migration mới luôn bắt đầu từ `V40`** (V39 là migration cuối hiện có). Kiểm tra `backend/src/main/resources/db/migration/` trước khi tạo.
2. **Idempotent**: dùng `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` trong SQL.
3. **Comment tiếng Anh** trong Java/SQL (đúng style repo hiện tại). Comment tiếng Việt chỉ dùng cho i18n JSON.
4. **Cập nhập cả `en.json` và `vi.json`** cùng lúc — repo có `scripts/i18n-check-dynamic.js` và `scripts/i18n-audit.js`.
5. **Mỗi hạng mục mới phải có**: DTO (`record`), repository method, service method, controller endpoint, frontend service method, frontend UI, i18n.
6. **Permission code mới** phải thêm vào **cả 3 nơi**:
   - `backend/.../common/security/PermissionCodes.java`
   - `frontend/src/services/permissions.ts`
   - migration `INSERT INTO permissions ...`
7. **Sau mỗi nhóm**: chạy `cd backend && mvn -q compile` và `cd frontend && npm run build`.

---

## 3. NHÓM S — Sửa logic sai (làm trước, ưu tiên cao)

### S1. Đưa review/publish về permission matrix thay vì hardcode role

**Vấn đề:** `AuthorizationService.requireStaffReviewer()` (L149) và `requireManagerApprover()` (L161) chỉ
check platform role. Nhưng `publishVersion()` cũng dùng `requireManagerApprover()` chứ không dùng
`DOCUMENT_VERSION_PUBLISH`.

**Sửa:**

1. `AuthorizationService` — thêm 2 method mới, giữ nguyên method cũ (để không phá call site khác):
   ```java
   /**
    * Guard cho tier-1 review, dựa trên permission matrix.
    * Yêu cầu cả {@code DOCUMENT_VERSION_REVIEW} lẫn workspace membership.
    */
   @Transactional(readOnly = true)
   public void requireDocumentReviewer(UUID userId, UUID workspaceId, String permissionCode) { ... }
   ```
   Thực chất nên để `DocumentReviewService` gọi thẳng `permissionService.requireAnyPermission(...)`
   + `workspaceService.requireMember(...)` — tách bạch "permission" và "membership" cho đúng triết lý
   2 trục. Giữ `requireStaffReviewer`/`requireManagerApprover` cho các chỗ khác.

2. `DocumentReviewService.submitStaffReview()`:
   ```java
   permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_VERSION_REVIEW);
   workspaceService.requireMember(actorId, document.getWorkspaceId());
   ```
   (`AuthorizationService` chưa inject `PermissionService` — thêm dependency, hoặc inject thẳng
   `PermissionService` vào `DocumentReviewService`.)

3. `DocumentReviewService.submitManagerDecision()`:
   `PermissionCodes.DOCUMENT_VERSION_APPROVE`.

4. `DocumentVersionService.publishVersion()`:
   `PermissionCodes.DOCUMENT_VERSION_PUBLISH` (thay `requireManagerApprover`).

5. **Đảm bảo ma trận seed đúng** — STAFF phải có `DOCUMENT_VERSION_REVIEW`, TECHNICAL_LEAD/MANAGER phải
   có `DOCUMENT_VERSION_APPROVE` + `DOCUMENT_VERSION_PUBLISH`. Kiểm tra `V16` (STAFF có
   `DOCUMENT_VERSION_REVIEW` ✅; MANAGER có APPROVE+PUBLISH ✅; TECHNICAL_LEAD có APPROVE+PUBLISH từ `V30` ✅)
   → thêm **migration V40** để chắc chắn & idempotent (tránh trường hợp admin đã sửa matrix trong UI).

6. **Frontend**: `ReviewQueuePage` dùng `queue.canStaffReview` (platform role) — đổi sang kiểm tra
   permission. Nhưng `pendingOnMe` response có `canStaffReview` là quyền thực thi server-side → nên
   để server là nguồn sự thật, và thêm `canStaffReview` vào `Capabilities` được tính từ **permission**
   thay vì platform role.

7. `AuthorizationService.capabilities()` — tính `canStaffReview` / `canManagerApprove` từ
   `permissionService.effectivePermissions()` (có `DOCUMENT_VERSION_REVIEW` / `DOCUMENT_VERSION_APPROVE`)
   thay vì từ `STAFF_CAPABLE`/`MANAGER_CAPABLE` set. **Phải giữ** `ADMIN` bypass.

> ⚠️ Lưu ý: `Capabilities` hiện là record dùng ở `ReviewQueueResponse` + endpoint
> `GET /workspaces/{id}/capabilities` + `frontend/src/services/drift.ts` → đổi nguồn tính không đổi shape.

**Verify:** admin tắt `DOCUMENT_VERSION_REVIEW` của STAFF trong `/admin/roles` → Staff gọi
`staff-review` phải nhận **403**.

---

### S2. Chặn self-review

**Vấn đề:** Staff submit draft của mình → Staff approve → tự forward lên Manager.

**Sửa** trong `DocumentReviewService.submitStaffReview()`:
```java
UUID submitter = version.getSubmittedByUserId();
if (submitter != null && submitter.equals(actorId)) {
    throw new ForbiddenException("You cannot review a version you submitted yourself");
}
```
- Kiểm tra áp dụng cho cả `APPROVED` lẫn `REJECTED`/`REQUEST_CHANGES`/`COMMENT`.
- **Ngoại lệ**: nếu workspace chỉ có 1 reviewer thì có thể deadlock. Cân nhắc cho phép nếu
  `reviewersFor(workspaceId)` trả về đúng 1 người và người đó là actor. *(Khuyến nghị: implement
  cảnh báo thay vì hard-block khi chỉ có 1 reviewer — hoặc đơn giản block luôn và ghi chép trong plan
  là quyết định sản phẩm; nếu chưa chốt thì hỏi user.)*

**Verify:** test `backend/src/test/java/com/livingdocs/modules/review/service/DocumentReviewServiceTest.java`
— `aReviewerMayNotReviewTheirOwnSubmission` + `theSelfReviewGuardAppliesToEveryDecision`
(STAFF submit bản của chính mình phải 403).

---

### S3. Manager chỉ approve được khi verdict Staff **mới nhất** là APPROVED

**Vấn đề:** `submitManagerDecision()` (L147-150) check `anyMatch(APPROVED)` trên toàn bộ lịch sử →
Staff approve rồi reject sau đó → Manager vẫn approve được.

**Sửa:**
```java
List<DocumentReview> staffReviews = reviewRepository
        .findAllByDocumentVersionIdAndReviewerRoleOrderByDecidedAtDesc(version.getId(), TIER_STAFF);
DocumentReview latestStaff = staffReviews.isEmpty() ? null : staffReviews.get(0);
boolean staffApproved = latestStaff != null && latestStaff.getDecision() == ReviewDecision.APPROVED;
if (!staffApproved) {
    throw new BadRequestException("Manager cannot approve before Staff approves");
}
```
**Bổ sung**: kiểm tra `version.getStatus() == VersionStatus.IN_REVIEW` trước khi Manager quyết định
(tương tự `submitStaffReview` đã check `PENDING || IN_REVIEW`).

---

### S4. `document.status` không được ghi đè bởi version cũ hơn

**Vấn đề:** `applyStaffVerdict()` / `applyManagerVerdict()` set `document.status` mà không kiểm tra
version đó còn là bản mới nhất.

**Sửa:** thêm helper trong `DocumentReviewService`:
```java
/**
 * Chỉ cập nhật trạng thái document khi version này vẫn là bản mới nhất.
 * Một version cũ hơn không được quyết định trạng thái của document.
 */
private void syncDocumentStatus(Document document, DocumentVersion version, DocumentStatus status) {
    var latest = versionRepository.findFirstByDocumentIdOrderByVersionNumberDesc(document.getId());
    if (latest.isEmpty() || latest.get().getId().equals(version.getId())) {
        document.setStatus(status);
    }
}
```
Thay mọi `document.setStatus(...)` trong `applyStaffVerdict`/`applyManagerVerdict`/`submitForReview`
bằng `syncDocumentStatus(...)`.

---

### S5. `dismissVersion`: Staff dismiss được draft của người khác (khớp V37)

**Vấn đề:** `DocumentVersionService.dismissVersion()` (L340-349) — `canApprove` chỉ gồm
`TECHNICAL_LEAD/MANAGER/ADMIN`, bỏ qua STAFF, dù V37 đã grant `DOCUMENT_VERSION_DISMISS` cho STAFF.

**Sửa:** dùng `AuthorizationService.STAFF_CAPABLE`-tương-đương:
```java
boolean canReview = authorizationService.platformRoles(actorId).stream().anyMatch(
        Set.of(AuthorizationService.ROLE_STAFF,
                AuthorizationService.ROLE_TECHNICAL_LEAD,
                AuthorizationService.ROLE_MANAGER,
                AuthorizationService.ROLE_ADMIN)::contains);
if (!isAuthor && !canReview) { throw new ForbiddenException(...); }
```
Hoặc sạch hơn: dùng `permissionService.hasAnyPermission(actorId, PermissionCodes.DOCUMENT_VERSION_REVIEW)`.

> ⚠️ Cần chốt với user: Staff có nên dismiss draft của người khác không? Theo spec "Staff = Developer +
> review", dismiss là hành động của **author**, nên có lẽ Staff dismiss draft người khác là **không** hợp lý.
> → **Hỏi user trước khi code.**

---

### S6. `rollback()` phải cập nhật head + status của document

**Vấn đề:** `DocumentVersionService.rollback()` (L370-390) tạo version `DRAFT` mới nhưng không đụng
`document.headVersionId` / `document.status`.

**Sửa:** giữ nguyên semantics "append-only, tạo version mới ở DRAFT" nhưng **không** đổi head
(version DRAFT chưa được publish thì head vẫn là bản published hợp lệ). Vấn đề thật là UI:
`DocumentDetailPage` hiển thị `doc.status` — sau rollback vẫn là `PUBLISHED` ✅ (đúng).
→ **Thực ra item này KHÔNG cần sửa về mặt head.** Nhưng cần kiểm tra: có nên cho rollback tạo version
`APPROVED` (đã qua review) để publish ngay không? → **Hỏi user.**

**Thay vào đó, sửa chắc chắn cần:** `rollback()` nên **copy `sourceCommitSha`/templateId** từ version
đích và ghi `changeSummary` đầy đủ hơn (hiện `"Rolled back to version N"` ✅ ổn).

**Ghi chú thêm**: `rollback()` cho phép target `PUBLISHED` hoặc `APPROVED`. Nếu document chưa từng publish
→ không rollback được. Chấp nhận.

---

### S7. Partial unique index chống double-review

**Vấn đề:** comment trong `V3__documentation_core.sql` nói *"only one OPEN record is allowed via the
partial unique index below"* nhưng index đó **không tồn tại** (grep toàn migrations: không có
`uq_document_reviews`).

**Sửa** — migration `V40__review_workflow_integrity.sql`:
```sql
-- Một version chỉ có nhiều review row khi chúng thuộc các tier khác nhau.
-- Trong cùng một tier, chỉ một review "đang mở" (chưa bị verdict sau đó ghi đè).
CREATE UNIQUE INDEX IF NOT EXISTS uq_document_reviews_open_per_tier
    ON document_reviews (document_version_id, reviewer_role)
    WHERE decision IN ('APPROVED', 'REJECTED', 'REQUEST_CHANGES');
```
⚠️ **Cần kiểm tra dữ liệu cũ**: nếu đã có 2 row cùng tier/decision thì index fail → phải có
bước dedupe trước (`DELETE` giữ row mới nhất) trong cùng migration.

**Bổ sung (race condition thật):** dùng `SELECT ... FOR UPDATE` trên `document_versions` trước khi
ghi verdict, hoặc thêm `@Version` optimistic lock trên `DocumentVersion`. Đơn giản nhất: trong
`submitStaffReview`/`submitManagerDecision`, load version với pessimistic write lock:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select v from DocumentVersion v where v.id = :id")
Optional<DocumentVersion> findByIdForUpdate(@Param("id") UUID id);
```

---

### S8. `markPendingForStaffReview()` — dead code

**Vấn đề:** method này **không nơi nào gọi** (grep toàn repo). `DocGenerationEventListener.persistVersion()`
để version ở `DRAFT` và để author tự submit. Trái spec: *"queue of AI-generated and auto-updated
documentation pending review"*.

**Hai lựa chọn — HỎI USER:**
- **(A) Bật lại:** gọi `markPendingForStaffReview()` trong `persistVersion()` khi version **không**
  auto-apply → AI doc tự vào queue Staff. Nhưng `markPendingForStaffReview` hiện **không set
  `submittedByUserId`/`submittedAt`** → author sẽ không thấy trong "My submissions" → phải sửa thêm
  (set submitter = `event.getRequestedByUserId()`).
- **(B) Xoá method** + giữ nguyên hành vi "author tự submit", và sửa comment trong `V35`/code để
  nói rõ AI doc cũng cần submit thủ công.

**Bonus (cùng nhóm):** `DocumentVersionRepository.findAllBySubmittedByUserIdAndDocumentIdInAndStatusInOrderBySubmittedAtDesc()`
(L46) cũng là dead code — xoá hoặc dùng. Xem B13.

---

### S9. `toggleAutoUpdate` dùng permission matrix

**Vấn đề:** `DocumentService.toggleAutoUpdate()` chỉ check `requireMember`, không dùng
`REPOSITORY_AUTO_UPDATE_MANAGE` (đã seed trong V37 cho DEVELOPER/STAFF/TECHNICAL_LEAD/MANAGER).

**Sửa:** thêm `permissionService.requireAnyPermission(actorId, PermissionCodes.REPOSITORY_AUTO_UPDATE_MANAGE)`.

> Lưu ý: đây là toggle **auto-update của 1 document**, còn `REPOSITORY_AUTO_UPDATE_MANAGE` là toggle
> auto-update của **repository**. Cân nhắc thêm permission riêng `DOCUMENT_AUTO_UPDATE_MANAGE`?
> → **Hỏi user** (nếu thêm thì phải seed + khai báo 3 nơi).

---

### S10. `DocumentService.update()` dùng `DOCUMENT_UPDATE`

**Vấn đề:** `update()` (L153-170) check owner hoặc workspace MANAGER, không dùng permission
`DOCUMENT_UPDATE` (đã seed cho DEVELOPER/STAFF/TECHNICAL_LEAD/MANAGER).

**Sửa:** thêm `permissionService.requireAnyPermission(actorId, PermissionCodes.DOCUMENT_UPDATE)`
(nhưng **giữ** luật owner-or-workspace-manager hiện tại, vì đó là luật nghiệp vụ).

---

### S11. `DocumentReviewController` dùng `workspaceId` trong path

**Vấn đề:** 6 endpoint nhận `{workspaceId}` nhưng không dùng → gây nhầm lẫn, dễ sót khi thêm endpoint.

**Sửa:** thêm check nhất quán trong `loadAndAuthorize()` hoặc một helper:
```java
private void requireSameWorkspace(UUID workspaceId, Document document) {
    if (!document.getWorkspaceId().equals(workspaceId)) {
        throw new NotFoundException("Document not found in this workspace");
    }
}
```
Áp dụng cho `submitForReview`, `submitStaffReview`, `submitManagerDecision`, `listReviews`.

---

### S12. Phát notification `REVIEW_ASSIGNED` / phân tách tier Staff vs Manager

**Vấn đề:** `NotificationKinds.REVIEW_ASSIGNED` tồn tại nhưng không được emit. `notifyReviewers()`
(L584) gửi `review.requested` cho **mọi** member có `canStaffReview()` — gồm MANAGER/ADMIN.

**Sửa (tối thiểu):**
- Tách 2 nhóm: `staffReviewers(workspaceId)` và `managerApprovers(workspaceId)`.
- Khi submit → notify **staff tier** (`review.requested`).
- Khi Staff APPROVED → notify **manager tier** (kind `REVIEW_ASSIGNED` hoặc `review.requested` với
  body khác).
- Khi Manager quyết định → notify submitter (đã có `notifySubmitter`).
- Cân nhắc bỏ "self-notify" cho người vừa hành động ở tầng kế tiếp (tránh MANAGER tự approve bản
  mình vừa submit).

---

## 4. NHÓM M — Bổ sung tính năng thiếu

### M1. Comment / request changes theo **section** cụ thể

**Yêu cầu #8.** Đây là thay đổi schema + API + UI lớn nhất.

**Schema** — migration `V41__review_section_comments.sql`:
```sql
ALTER TABLE document_reviews
    ADD COLUMN IF NOT EXISTS section_id   VARCHAR(120),
    ADD COLUMN IF NOT EXISTS section_heading VARCHAR(255),
    ADD COLUMN IF NOT EXISTS section_anchor VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_document_reviews_section
    ON document_reviews (document_version_id, section_id);
```

**Backend:**
- `DocumentReview` — thêm 3 field + getter/setter.
- `SubmitReviewRequest` — thêm `String sectionId`, `String sectionHeading`, `String sectionAnchor`.
  (`@Size(max = 255)`)
- `DocumentReviewResponse` — thêm 3 field → cập nhật `from()`.
- `DocumentReviewService.submitStaffReview()` / `submitManagerDecision()` — truyền section xuống
  constructor `DocumentReview`.
- **Thêm endpoint mới** (không bắt buộc, nhưng nên có) để lấy comment theo section:
  `GET /workspaces/{id}/documents/{did}/versions/{vid}/reviews` đã trả về tất cả → frontend tự group
  theo `sectionId`.

**Cơ chế xác định section:**
- Tài liệu là **markdown**. Section = **markdown heading** (`## Overview`).
- `sectionId` = slug của heading (`overview`), hoặc template section `id` nếu template có
  (`sections[].id`).
- `sectionAnchor` = anchor GitHub-style (`#overview`) để frontend deep-link.
- Backend nên **validate**: heading có thực sự tồn tại trong `body_markdown` của version không
  → nếu không thì `BadRequestException`. (Tránh comment treo lơ lửng.)
- Cần helper parse heading từ markdown: regex `^#{1,6}\s+(.+)$` theo dòng.

**Frontend:**
- `services/drift.ts`: thêm field vào `DocumentReview` interface + `SubmitReviewRequest` type.
- `ReviewQueuePage.tsx`: thêm **review drawer / modal** mở khi bấm vào 1 row:
  - hiện body markdown của version (rendered đơn giản hoặc raw),
  - bấm vào heading → chọn section đó (highlight),
  - textarea comment gắn với section đang chọn,
  - bấm "Approve" / "Request changes" / "Comment" → gửi kèm section.
- `DocumentDetailPage.tsx`: hiển thị comment theo section (anchor link tới heading).

**i18n:** thêm key cho `reviews.section.*`, `reviews.commentOnSection`, `reviews.pickSection` — cả
`en.json` + `vi.json`.

---

### M2. Filter & sort review queue

**Yêu cầu #9.** Hiện tại load toàn bộ workspace rồi filter in-memory.

**Backend — `DocumentReviewRepository` / `DocumentVersionRepository`:**

Thêm query có filter thật sự (tránh N+1):
```java
// DocumentVersionRepository
List<DocumentVersion> findAllByDocumentIdInAndStatusOrderBySubmittedAtDesc(
        Collection<UUID> documentIds, VersionStatus status);
```
Filter theo `submittedAt` (đã có cột từ V39, có index `idx_document_versions_submitter`).

> ⚠️ **Bug hiện có (B14):** `ReviewQueueItemResponse.createdAt` đang map từ `version.getCreatedAt()` —
> tức là **thời điểm version được tạo**, KHÔNG phải lúc submit. Sort "theo ngày submit" sẽ ra kết quả
> sai. Phải expose `submittedAt` riêng (cột đã có sẵn trong DB từ V39).

**Thêm query param** vào `GET /workspaces/{id}/reviews/staff-queue` và `/pending-on-me`:
| Param | Ý nghĩa |
|---|---|
| `repositoryId` | UUID | lọc theo repository (join qua `documents.repository_id`) |
| `templateId` | UUID | lọc theo template (join qua `documents.template_id`) |
| `severity` | `LOW/MEDIUM/HIGH/CRITICAL` | lọc theo drift severity cao nhất của document |
| `submittedFrom` / `submittedTo` | ISO date | lọc theo ngày submit |
| `docType` | string | (bonus) lọc theo loại tài liệu |
| `sort` | `submittedAt` / `confidence` / `severity` | hướng sort |
| `direction` | `asc`/`desc` | |

**Drift severity:** cần map document → max severity của `drift_alerts` còn OPEN.
`DriftAlertRepository` đã có `findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc` — dùng để
gom nhóm theo `documentId` trong 1 query, không N+1.

**DTO — `ReviewQueueItemResponse`** thêm:
```java
UUID repositoryId,
UUID templateId,
String templateName,
String maxDriftSeverity,     // null nếu không có drift alert
OffsetDateTime submittedAt,
String submittedByUserId,
String submittedByName,
```

**Frontend:**
- `services/drift.ts`: `ReviewQueueItem` interface thêm field; `staffQueue(workspaceId, filters?)`,
  `pendingOnMe(workspaceId, filters?)` nhận query object.
- `ReviewQueuePage.tsx`: thêm **filter bar** (Select cho repository / template / severity / docType +
  Date range + sort select). Dùng component `Select` đã có sẵn (`components/ui/select.tsx`).
- i18n key mới.

**Hiệu năng:** `itemsForWorkspaceStatus()` hiện là:
```java
for (Document doc : documents) {
    for (DocumentVersion v : versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(doc.getId())) { ... }
}
```
→ **1 query / document**. Phải đổi sang 1 query tổng: `findAllByDocumentIdInAndStatus(...)`.

---

### M3. Verify template structure (compliance check)

**Yêu cầu #5.** Cần so heading trong `body_markdown` của version với `sections[].heading` của template.

**Template resolution:** `Document.templateId` → `DocTemplate.body` (JSON). Nếu `templateId == null` →
fallback: `DocTemplateService.resolveDefault(workspaceId, docType)`.

**Logic:**
1. Parse `sections[]` từ `template.body` (dùng `TemplateSchema.normalise()` để có canonical shape).
2. Parse heading từ `body_markdown`: regex `^#{1,6}\s+(.+)$` → tập heading text (normalize: lowercase,
   bỏ dấu câu, collapse whitespace).
3. Với mỗi section:
   - `present`: có heading khớp → ✅
   - `missing`: không có → ❌
   - `order`: vị trí heading trong doc so với thứ tự template → ⚠️ nếu sai thứ tự
   - `empty`: heading có nhưng nội dung dưới nó rỗng → ⚠️
4. `compliant = missing.isEmpty() && outOfOrder.isEmpty()`

**API mới:**
```
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/template-compliance
```
Response:
```java
public record TemplateComplianceResponse(
    UUID templateId,
    String templateName,
    boolean compliant,
    List<SectionCompliance> sections,   // id, heading, present, inOrder, empty, required
    List<String> missingHeadings,
    List<String> outOfOrderHeadings
) {}
```

**Frontend:**
- `DocumentDetailPage`: badge "Template ✓ / ✗ (thiếu N section)" cạnh version.
- `ReviewQueuePage`: cột "Template" hiển thị trạng thái compliance → Staff thấy ngay trong queue
  mà không cần mở document.

**Quyền truy cập:** endpoint chỉ cần workspace membership (mọi role trong workspace đều xem được).

---

### M4. Verify nội dung nhất quán với source code (drift trong review queue)

**Yêu cầu #6.**

**Cách 1 (rẻ, khuyến nghị):** đưa `maxDriftSeverity` + số alert OPEN vào `ReviewQueueItemResponse`
(đã nằm trong M2) → Staff thấy ngay trong queue. Thêm link tới tab Drift của document.

**Cách 2 (đắt hơn):** thêm endpoint `POST .../versions/{vid}/consistency-check` gọi AI service để
đối chiếu nội dung với `grounding_json` (source files + code entities). AI service hiện **chưa có**
endpoint verify (grep `ai-service` không có `compliance`/`verify`) → cần viết mới ở
`ai-service/app/api/v1/generate.py` hoặc file mới.

**Khuyến nghị:** làm Cách 1 trước (rẻ, giá trị cao), để Cách 2 làm sau nếu còn thời gian.

---

### M5. Ticket (Jira) reference cho grounding evidence

**Yêu đều #2 (một phần).**

Hiện `document_versions` không lưu ticket nào. `JiraWebhookHandler` có xử lý event nhưng chỉ tạo
`OutboundNotification`, không gắn vào document.

**Cách nhẹ nhất:** thêm cột nullable trên `document_versions`:
```sql
ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS source_ticket_ref VARCHAR(120);   -- ví dụ "PROJ-123"
```
- Fill từ đâu? Khi generate từ PR có body chứa "PROJ-123" → regex `/\b([A-Z][A-Z0-9]+-\d+)\b/` trên
  commit message / PR title. Hoặc từ `DocGenerationRequestedEvent.sourcePrId` → tra PR body.
- Hiển thị trong grounding panel của `DocumentDetailPage` + cột trong review queue.

**Cách đầy đủ hơn:** bảng `document_tickets` (document_id, ticket_key, ticket_url, source) + populate
từ Jira webhook. Khá nặng.

**Khuyến nghị:** làm Cách nhẹ nhất (regex trên PR/commit), kèm UI hiển thị.

---

### M6. Inline edit + change log chi tiết trong review screen

**Yêu cầu #7 và #10.**

**M6a — Review drawer với change log chi tiết:**
- Mở drawer từ 1 row của review queue: hiện timeline đầy đủ của document
  (`GET /documents/{id}/versions`), diff so với bản published
  (`GET /documents/{id}/diff?comparedTo=published`), grounding evidence, template compliance.
- Dùng lại component có sẵn: `UnifiedDiffViewer.tsx`, `VersionHistoryDialog.tsx`.

**M6b — Inline edit:**
- Cho phép Staff sửa body của **version đang review** → tạo version mới **thay thế** version đang
  review (giữ nguyên `submittedByUserId`, `status=PENDING`), version cũ → `SUPERSEDED`.
- **Cần API mới**:
  ```
  PUT /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{versionId}/revise
  body: { bodyMarkdown, changeSummary }
  ```
  Logic trong `DocumentReviewService` (hoặc `DocumentVersionService`):
  1. load version, phải `status == PENDING` (đang chờ Staff review)
  2. guard: actor có `DOCUMENT_VERSION_REVIEW` + `DOCUMENT_UPDATE`
  3. tạo version mới `versionNumber + 1`, `actorRole` từ `actorRoleFor()` (STAFF), `status=PENDING`,
     `submittedByUserId` = submitter gốc, `submittedAt` = giữ nguyên
  4. version cũ → `SUPERSEDED`
  5. audit log `document.review_revised`
- **Đây là thay đổi lớn** — cân nhắc tách riêng, không gộp với nhóm S.

---

## 5. NHÓM L — Polish UI & i18n

- `ReviewQueuePage`: thêm cột "Repository", "Template", "Submitted at", "Drift severity".
- Thêm nút **"Reject"** (hiện UI chỉ có Approve / Request changes / Publish — `REJECTED` là enum
  nhưng **không có nút** trong `ReviewQueuePage.tsx`; xem L305/L326/L334).
- Thêm nút "Comment only" (`ReviewDecision.COMMENT` backend đã hỗ trợ, UI chưa có).
- Empty state riêng cho từng filter.
- i18n `en.json` + `vi.json` cho tất cả key mới.
- Chạy `node scripts/i18n-check-dynamic.js` và `node scripts/i18n-audit.js` để chắc không sót key.

---

## 6. Kiểm thử

### 6.1 Unit test (JUnit) — thêm vào `backend/src/test/java/`

| File | Test |
|---|---|
| `modules/review/DocumentReviewServiceTest.java` | Staff approve → version `IN_REVIEW`; Staff reject → `REJECTED`; Manager approve khi Staff chưa approve → `BadRequestException`; Manager approve sau Staff approve rồi reject → `BadRequestException`; self-review → `ForbiddenException`; Staff không có `DOCUMENT_VERSION_REVIEW` → `ForbiddenException` |
| `modules/version/DocumentVersionServiceReviewTest.java` | `dismissVersion` với STAFF; `rollback` tạo version đúng `actorRole`; `publishVersion` cần `DOCUMENT_VERSION_PUBLISH` |
| `modules/review/TemplateComplianceTest.java` | heading thiếu / sai thứ tự / rỗng → `compliant=false` |

Test hiện có để tham chiếu style: `backend/src/test/java/com/livingdocs/common/security/AuthorizationServiceTest.java`.

### 6.2 Verify

Các case này đã được cover bằng JUnit test trong
`backend/src/test/java/com/livingdocs/modules/review/service/DocumentReviewServiceTest.java`
và `ReviewDelegationServiceTest.java`:
- STAFF bị chặn khi thiếu `DOCUMENT_VERSION_REVIEW`.
- Self-review bị 403.
- Manager approve khi Staff verdict mới nhất = `REQUEST_CHANGES` → 400.
- Staff dismiss draft của người khác → 200 (nếu chốt là cho phép).
- Template compliance endpoint trả kết quả đúng.
- Filter/sort queue trả đúng tập kết quả.

Nếu cần kiểm chứng end-to-end trên stack thật, chạy `node scripts/integration-check.js`.

### 6.3 Kiểm tra build

```powershell
cd backend; mvn -q clean compile
cd frontend; npm run build
```

---

## 7. Checklist nghiệm thu (13 yêu cầu STAFF)

Sau khi sửa xong, đánh giá lại từng yêu cầu:

| # | Yêu cầu | Trạng thái sau sửa |
|---|---|---|
| 1 | Xem queue AI-generated + auto-updated | ☐ S8 (A) bật lại auto-submit AI doc → queue có AI doc |
| 2 | Mở doc + grounding (source file, code entity, commit, diff, **ticket**) | ☐ M5 thêm ticket |
| 3 | Change log (AI changes + human edits) | ☐ M6a đưa vào review drawer |
| 4 | So sánh với bản approved cuối | ☐ M6a đưa vào review drawer |
| 5 | Verify template structure | ☐ M3 |
| 6 | Verify nhất quán với source code | ☐ M4 cách 1 (drift severity trong queue) |
| 7 | Sửa lỗi nhỏ trong lúc review | ☐ M6b |
| 8 | Comment / request changes theo section | ☐ M1 |
| 9 | Filter & sort queue | ☐ M2 (bao gồm fix B14: `submittedAt` đúng nghĩa) |
| 10 | Lịch sử review + trạng thái | ☐ đã có, kiểm tra lại |
| 11 | Approve → forward Manager | ☐ đã có + S3 |
| 12 | Reject → trả về Developer kèm feedback | ☐ đã có + thêm nút Reject (nhóm L) |
| 13 | STAFF = DEVELOPER + review | ☐ S5, S9, S10 |

---

## 8. Các câu hỏi cần chốt với user TRƯỚC khi code

1. **S5** — Staff có được dismiss draft của người khác không? (V37 đã grant permission nhưng logic
   chưa cho phép.)
2. **S8** — AI-generated doc có tự vào queue Staff không, hay tác giả phải bấm "Submit for review"?
3. **S2** — Chặn self-review tuyệt đối hay ngoại lệ khi workspace chỉ có 1 reviewer?
4. **S9** — Có thêm permission riêng `DOCUMENT_AUTO_UPDATE_MANAGE` cho toggle auto-update của 1
   document không, hay dùng chung `REPOSITORY_AUTO_UPDATE_MANAGE`?
5. **S6** — Version tạo ra bởi `rollback` có nên là `APPROVED` (publish được ngay) hay `DRAFT` (phải
   qua lại review)?
6. **M4 cách 2** — Có đầu tư làm consistency-check bằng AI (cần viết mới endpoint ở `ai-service`)
   không, hay tạm dừng ở drift severity?
7. **M6b** — Có chấp nhận thay đổi lớn (version revision API) trong đợt này không?

---

## 9. PROMPT CHO CHAT MỚI

> Copy nguyên văn khối dưới đây, dán vào chat mới cùng workspace `d:\Project\LivingDocs`.

---

Tôi cần bạn thực hiện kế hoạch sửa cho vai trò **STAFF (review tier-1)** của dự án LivingDocs.

**Đọc trước file kế hoạch đầy đủ:** `docs/plan-staff-review-role.md` ở thư mục gốc repo
(`d:\Project\LivingDocs`). File đó chứa toàn bộ phân tích hiện trạng, danh sách bug logic
(Phần 3 — NHÓM S), tính năng còn thiếu (Phần 4 — NHÓM M), và checklist nghiệm thu (Phần 7).

**Bối cảnh quan trọng:**
- LivingDocs dùng mô hình 2 trục: platform role (`user_roles`) + workspace role
  (`workspace_members.role`), enforcement là AND. Ngoài ra có permission matrix
  (`permissions` + `role_permissions`) resolve qua `PermissionService`.
- **Quy tắc bất di bất dịch của repo:** capability đã có trong hệ thống thì phải dùng permission
  matrix, **không** hardcode platform role. Xem comment đầu `V36__capability_permissions.sql`.
- Migration mới bắt đầu từ `V40`. Comment trong Java/SQL viết tiếng Anh; i18n thì cần sửa **cả**
  `frontend/src/i18n/en.json` và `frontend/src/i18n/vi.json`.
- Trạng thái repo: branch `XuanDu`, commit `7e105cd`, working tree sạch.

**Cách làm — làm tuần tự, mỗi nhóm là 1 commit riêng, build phải pass trước khi sang nhóm sau:**

1. **Trước tiên, hỏi tôi các câu hỏi ở Phần 8 của file kế hoạch** (7 câu quyết định: dismiss
   cross-author, auto-submit AI doc, self-review, permission auto-update, rollback status,
   AI consistency-check, version revision API). Trả lời sẽ quyết định logic của S2/S5/S6/S8/S9/M4/M6b.
2. **Nhóm S — sửa logic sai** (S1→S12). Đây là phần ưu tiên cao nhất vì đang có hành vi sai.
3. **Nhóm M — bổ sung tính năng thiếu** (M1 comment theo section, M2 filter/sort queue,
   M3 template compliance, M4 drift severity trong queue, M5 ticket reference,
   M6 review drawer + inline edit). Bắt đầu từ M2 và M3 vì chúng có giá trị cao/effort thấp.
4. **Nhóm L — polish UI + i18n.**
5. **Testing:** thêm JUnit test vào `backend/src/test/java/`, chạy `mvn clean compile` + `npm run build`.
6. **Cuối cùng, đánh giá lại** theo checklist 13 yêu cầu ở Phần 7 và báo cáo:
   mỗi yêu cầu đã đạt / chưa đạt / còn hạn chế, kèm file + dòng code làm bằng chứng.

**Nguyên tắc khi sửa:**
- Sửa **đúng file hiện có** trước; chỉ tạo file mới khi thật sự cần.
- Không bỏ các guard đang hoạt động; nếu thay đổi semantics thì cập nhật Javadoc + comment.
- Mỗi thay đổi phải kèm giải thích lý do (vì sao logic cũ sai).
- Sau mỗi nhóm: `cd backend; mvn -q compile` và `cd frontend; npm run build` phải pass.
- Báo cáo cuối bằng tiếng Việt, ngắn gọn, kèm danh sách file đã đổi.

Bắt đầu bằng việc đọc file kế hoạch và hỏi tôi 7 câu hỏi ở Phần 8.
