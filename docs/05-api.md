# 05 — REST API 清单

统一前缀 `/api/v1`，鉴权 JWT（`Authorization: Bearer`）。**客户端不能直接传入业务状态**，只能传入操作意图，后端推导目标状态。

## 5.0 通用约定

- 请求：JSON（查询用 query param）。
- 返回统一包装：`{ code, message, data }`；错误时 `code` 非 0。
- 分页：`page` / `size`。
- 幂等：交接/归还确认接口以 `biz_id + event + user` 幂等（重复提交返回当前状态，不重复写库）。
- 权限：所有写操作在 Service 层显式校验资源归属，越权返回 `403 FORBIDDEN`。
- 状态非法：返回 `409 CONFLICT`（重复预留、时段冲突）；资源不存在：`404`。
- **角色术语统一**：涉及安排的双方称「安排双方」，不使用「成交双方」等交易语义。

## 5.1 用户 API

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| POST | /auth/sms-code | 游客 | 发送验证码 | 限流 | 否 |
| POST | /auth/login | 游客 | 验证码登录，返回 JWT | — | 否 |
| GET | /users/me | 登录 | 当前用户信息 | 本人 | — |
| PATCH | /users/me | 登录 | 改昵称/头像/简介 | 本人 | 否 |
| GET | /users/{id} | 登录 | 查看他人公开资料 | 公开 | — |

## 5.2 宠物档案 API

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| POST | /pets | 普通用户 | 发布宠物档案（来源/所有权/授权声明、图片、位置） | 本人 | 否 |
| PATCH | /pets/{id} | 发布者 | 编辑档案 | 本人 | 否 |
| GET | /pets/{id} | 全部 | 档案详情（模糊位置） | 公开 | — |
| POST | /pets/{id}/archive | 发布者/运营 | 归档 | 本人/运营 | 否 |
| POST | /pets/{id}/delete | 发布者/运营 | 软删除 | 本人/运营 | 否 |
| GET | /pets/{id}/exact-location | 被授权者/运营 | 精确位置（需 `location_access_grants` 授权） | 授权+审计 | — |
| POST | /pets/{id}/location-grants | 授权者 | 授予精确位置访问 | owner/发布者 | 否 |
| POST | /location-grants/{id}/revoke | 授权者 | 撤销授权 | 授权者 | 否 |

## 5.3 发布信息 API（寻主/领养/照护）

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| POST | /pet-posts | 普通用户 | 发布信息（post_type=FINDING/ADOPTION/FOSTER） | 本人 + 处置授权校验 | 否 |
| GET | /pet-posts | 全部 | 列表（类型/物种/距离筛选） | 公开 | — |
| GET | /pet-posts/{id} | 全部 | 详情（模糊位置） | 公开 | — |
| PATCH | /pet-posts/{id} | 发布者 | 编辑（未锁定/未完成时） | 本人 | 否 |
| POST | /pet-posts/{id}/close | 发布者/运营 | 下架 | 本人/运营 | 否 |
| POST | /pet-posts/{id}/convert-to-adoption | 发布者 | 寻主转领养（新建 ADOPTION 帖子） | 本人，需已核实 | 否 |
| POST | /pet-posts/{id}/cancel | 发布者 | 取消 | 本人 | 否 |

## 5.4 申请 API

> 路径约定：发布信息统一存储在 `pet_posts`（以 `post_type` 区分）。路径中的 `adoption-posts`、`foster-posts`、`finding-posts` 均映射到 `pet_posts` 并按 `post_type` 过滤；申请落到各自独立的 `adoption_applications` / `foster_applications` 表。

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| POST | /adoption-posts/{id}/applications | 普通用户 | 提交领养申请 | 登录 | 否（防重提交） |
| POST | /adoption-applications/{id}/withdraw | 申请人 | 撤回 | 本人 | 否 |
| POST | /adoption-applications/{id}/approve | 发布者 | 通过→创建安排+批量拒绝其余 | 帖子发布者 + 处置授权 | 否 |
| POST | /adoption-applications/{id}/reject | 发布者 | 拒绝 | 帖子发布者 | 否 |
| POST | /foster-posts/{id}/applications | 普通用户 | 提交照护申请（可含提议时段） | 登录 | 否（防重提交） |
| POST | /foster-applications/{id}/withdraw | 照护人 | 撤回 | 本人 | 否 |
| POST | /foster-applications/{id}/approve | 主人 | 通过→创建工单（写最终时段） | 帖子发布者 == owner | 否 |
| POST | /foster-applications/{id}/reject | 主人 | 拒绝 | 帖子发布者 == owner | 否 |

## 5.5 交接 API（交接记录由服务端维护，客户端只提交确认意图）

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| POST | /adoption-arrangements/{id}/confirm-handover | 安排双方 | 确认领养交接（服务端按 JWT 识别 from/to） | 安排双方 | 是 |
| POST | /adoption-arrangements/{id}/cancel | 安排双方 | 取消安排 | 任一方 | 否 |
| POST | /adoption-arrangements/{id}/abort | 安排双方/运营 | 异常终止（走失/受伤） | 任一方发起，运营确认 | 否 |
| POST | /foster-orders/{id}/confirm-handover | 安排双方 | 确认照护交接（照护开始） | 安排双方 | 是 |
| POST | /foster-orders/{id}/confirm-return | 安排双方 | 确认归还（照护结束） | 安排双方 | 是 |
| POST | /foster-orders/{id}/extend | 安排双方 | 申请延期（重校验冲突） | 双方同意 | 否 |
| POST | /foster-orders/{id}/cancel | 安排双方 | 取消照护安排 | 任一方 | 否 |
| POST | /foster-orders/{id}/abort | 安排双方/运营 | 异常终止 | 任一方发起，运营确认 | 否 |

> 交接记录（`handover_records`）在创建安排/工单的**同一事务内预创建**（确认位 `false`）：领养预创建 `HANDOVER`，照护预创建 `HANDOVER` + `RETURN`；`confirm-handover` / `confirm-return` 接口**只更新确认位、不 INSERT**。`abort` 接口懒创建 `ABORT` 记录（`ON CONFLICT DO NOTHING`）。**不提供普通用户直接创建交接记录的接口**。

## 5.6 消息 API

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| GET | /conversations | 登录 | 会话列表 | 参与者 | — |
| POST | /conversations | 登录 | 发起会话（关联帖子） | 登录 | 是（防重复会话） |
| GET | /conversations/{id}/messages | 登录 | 消息列表 | 参与者 | — |
| POST | /conversations/{id}/messages | 登录 | 发送消息 | 参与者 | 否 |
| POST | /messages/{id}/read | 登录 | 标记已读 | 接收者 | 是 |

## 5.7 运营 API

| 方法 | 路径 | 角色 | 说明 | 权限 | 幂等 |
|---|---|---|---|---|---|
| POST | /reports | 登录 | 举报 | 本人 | 否 |
| GET | /admin/reports | 运营/管理员 | 举报列表 | 运营 | — |
| POST | /admin/reports/{id}/handle | 运营/管理员 | 举报处理 | 运营 | 否 |
| POST | /admin/posts/{id}/take-down | 运营/管理员 | 内容下架 | 运营 | 否 |
| POST | /admin/users/{id}/ban | 管理员 | 封禁用户 | 管理员 | 否 |
| POST | /admin/pets/{id}/exact-location | 运营/管理员 | 精确位置特殊访问（授权+审计） | 特殊授权 | 否 |

## 5.8 请求/返回示例（领养申请审核）

```
POST /api/v1/adoption-applications/123/approve
Authorization: Bearer <jwt>

→ 成功 200
{ "code": 0, "data": { "arrangementId": 456, "postStatus": "RESERVED", "rejectedOthers": 2 } }

→ 宠物已被预留 409
{ "code": 40901, "message": "该宠物已被预留" }

→ 越权 403
{ "code": 40300, "message": "仅帖子发布者且具备处置授权可审核申请" }
```

## 5.9 已删除的支付相关 API

以下 API **已从设计移除**：创建支付、查询支付、支付回调、退款申请、退款审核、资金解冻、交易记录等。
