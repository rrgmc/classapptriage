# ClassApp API Specification

A language-agnostic specification of the ClassApp (classapp.com.br) API as used
by the ClassApp web application, sufficient to implement a client in any
programming language. It documents the transport, authentication flow, every
operation, the data shapes, and the platform's conventions and quirks.

> **Status.** The endpoint is undocumented by ClassApp. Everything here was
> reverse-engineered from the official web client and is offered as-is. The
> server's schema can change without notice; verify against live responses.

---

## 1. Transport

### Endpoint

All operations are GraphQL documents sent to a **single endpoint**:

```
POST https://web.classapp.com.br/graphql
```

### Query parameters

Every request carries these URL query parameters (the web client sends them on
every call):

| Parameter | Example | Meaning |
| --- | --- | --- |
| `client_id` | `ZmYyYWM3M2JmYjkxY2IwZWJhNzlhZjcw` | Web-client identifier. The value above is the one the web app uses. |
| `tz_offset` | `-180` | Client timezone offset in **minutes** (`-180` = UTC−3, America/São_Paulo). |
| `locale` | `pt` | UI locale (`pt`, `en`, …); affects the language of error `message`s. Note: `statusText` is **not** localized in practice (see §10.1). |

Example:

```
https://web.classapp.com.br/graphql?client_id=ZmYyYWM3M2JmYjkxY2IwZWJhNzlhZjcw&tz_offset=-180&locale=pt
```

### Headers

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `Accept` | `*/*` |
| `Authorization` | `Bearer <accessToken>` — **omit entirely** for the authentication mutations in §3, which run before a token exists. Required for every other operation. |
| `User-Agent` | Any non-empty string. |

### Request body

Standard GraphQL-over-HTTP JSON:

```json
{
  "operationName": "ViewerQuery",
  "query": "query ViewerQuery { viewer { id: dbId } }",
  "variables": { "entityId": 123456789 }
}
```

- `operationName` is optional but recommended (matches the operation in `query`).
- `variables` is omitted or `{}` when the operation takes none.

### Response envelope

Responses follow the GraphQL envelope:

```json
{
  "data": { "...": "..." },
  "errors": [ { "message": "…", "name": "…" } ]
}
```

`data` is present on success; `errors` is present on failure (see §2).

---

## 2. Error handling

**Critical quirk:** the ClassApp server returns GraphQL-level errors with a
**non-200 HTTP status** (e.g. `400`, `401`), not the usual `200`. A correct
client must therefore:

1. Read the full response body regardless of status code.
2. Attempt to decode it as the GraphQL envelope.
3. If the body decodes and `errors` is non-empty → surface those errors,
   **even though the HTTP status is not 200**.
4. Otherwise, if the HTTP status is not 200 → treat it as a transport/gateway
   error (the body may be HTML, e.g. from Cloudflare).
5. Otherwise decode `data`.

Each entry in `errors` has:

| Field | Type | Meaning |
| --- | --- | --- |
| `message` | string | Human-readable error, localized per `locale`. |
| `name` | string | Error class, e.g. `GraphQLError`. |

Example (served with HTTP `401`):

```json
{ "errors": [ { "message": "Invalid credentials", "name": "GraphQLError" } ] }
```

Errors are **not always** non-200: an authenticated-field failure can come
back as HTTP `200` with both `errors` and a partial `data` (observed:
`{"errors":[{"message":"Not authorized",…}],"data":{"node":null}}` for a
`node` query without a token). Checking `errors` first, as above, handles both.

Recommended client behavior: represent a non-empty `errors` array as a distinct
error type (e.g. `GraphQLErrors`) carrying all entries, distinguishable from
network/HTTP errors.

---

## 3. Authentication

Authentication yields a **bearer access token** (`accessToken`) used in the
`Authorization` header of all subsequent requests. There is also a
`refreshToken`; the web client uses it to renew the access token (the renewal
mutation is not documented here).

All authentication mutations run **without** an `Authorization` header.

The account may authenticate by **password**, by a **one-time code** (sent to
email/SMS), or a password login may additionally require a one-time code
(`requiresOtp`). The flows below cover all three.

### 3.1 Discover the authentication method (optional)

```graphql
mutation GetAuthenticationMethod($email: String, $phone: String) {
  getAuthenticationMethod(input: {email: $email, phone: $phone}) {
    authenticationMethod
    isNewUser
    passwordlessKilledForUser
  }
}
```

Provide **exactly one** of `email` or `phone`. Returns:

| Field | Type | Meaning |
| --- | --- | --- |
| `authenticationMethod` | string | e.g. `password`, or a passwordless/code method. |
| `isNewUser` | boolean | The contact has no account yet. |
| `passwordlessKilledForUser` | boolean | Passwordless disabled for this user. |

### 3.2 Password login

```graphql
mutation passwordAuthenticate($input: PasswordAuthenticateInput!) {
  passwordAuthenticate(input: $input) {
    requiresOtp
    user {
      id: dbId
      language
      isMaster
      hasPassword
      oauthProvider { accessToken refreshToken }
    }
  }
}
```

`PasswordAuthenticateInput` fields:

| Field | Type | Notes |
| --- | --- | --- |
| `email` | String | Provide this **or** `phone`, not both. |
| `phone` | String | " |
| `password` | String | Required. |

Result:

- `requiresOtp = false` → `user.oauthProvider.accessToken` is the usable bearer
  token. Done.
- `requiresOtp = true` → password accepted but a one-time code is still
  required. The `accessToken` is **not** usable yet; proceed to §3.3 then §3.4.

### 3.3 Request a one-time code

```graphql
mutation sendCode($email: String, $phone: String) {
  sendCode(input: {email: $email, phone: $phone, invite: true, source: WEB, isNewCode: true}) {
    __typename
  }
}
```

Provide exactly one of `email` or `phone`. Sends a numeric code to that address
by email or SMS. Used both for passwordless login and to satisfy a
`requiresOtp` result.

The literal input fields `invite: true`, `source: WEB`, `isNewCode: true` match
the web client; `source` is an enum (`WEB`).

### 3.4 Complete login with the code

```graphql
mutation codeAuthenticate($input: CodeAuthenticateInput!) {
  codeAuthenticate(input: $input) {
    user {
      id: dbId
      language
      isMaster
      hasPassword
      oauthProvider { accessToken refreshToken }
    }
  }
}
```

`CodeAuthenticateInput` fields:

| Field | Type | Notes |
| --- | --- | --- |
| `code` | String | The code the user received. |
| `address` | String | The **same** email or phone the code was sent to. |

`user.oauthProvider.accessToken` is the usable bearer token.

### Flow summary

```
                 ┌─ requiresOtp=false ─► accessToken ✔
password ──►────►┤
                 └─ requiresOtp=true ──► sendCode ──► codeAuthenticate ─► accessToken ✔

code-only ─────► sendCode ──────────────────────────► codeAuthenticate ─► accessToken ✔
```

---

## 4. Conventions

### 4.1 IDs — the `dbId` alias

ClassApp objects expose two identifiers: an opaque Relay node id (`id`) and a
**numeric database id** (`dbId`). This API is driven by the numeric ids. All
documents alias the numeric id back onto the response key `id`:

```graphql
id: dbId
```

so every `id` in a response is the **numeric** id. Crucially, the top-level
`node(id: …)` field also accepts these **numeric** ids as its `ID!` argument
(e.g. `node(id: 123456789)`), not only Relay node ids. Clients pass numeric
ids everywhere.

### 4.2 Connections

List fields are Relay-style connections shaped `{ nodes: [...], pageInfo {...} }`.
Clients typically flatten `nodes` into a plain list. `pageInfo` carries
`hasPreviousPage` / `hasNextPage` booleans.

### 4.3 Timestamps

Timestamps (`created`, `sentAt`) are ISO-8601 / RFC-3339 strings.

### 4.4 Well-known ids are account-specific

Entity ids and label ids differ per account and per organization. **Do not hard
code them.** Discover entity ids via `viewer.entities` (§5.1) and label ids via
the labels query (§5.2).

---

## 5. Operations

Each operation below lists its GraphQL document, its variables, and the shape
of the `data` it returns. All require the `Authorization` header.

### 5.1 Viewer — current user and their entities

```graphql
query ViewerQuery {
  viewer {
    id: dbId
    fullname
    email
    phone
    language
    created
    entities {
      nodes {
        id: dbId
        fullname
        type
        disabled
        organization { id: dbId fullname }
      }
    }
  }
}
```

Variables: none.

Returns `data.viewer` (see `Viewer` in §7). Each `viewer.entities.nodes[].id`
is an **entity id** — the value the message operations take as `entityId`.

### 5.2 Labels — organization message labels

```graphql
query EntityLabelsQuery($entityId: ID!, $limit: Int) {
  node(id: $entityId) {
    ... on Entity {
      id: dbId
      organization {
        id: dbId
        labels(limit: $limit) {
          nodes { id: dbId title color }
        }
      }
    }
  }
}
```

Variables:

| Variable | Type | Notes |
| --- | --- | --- |
| `entityId` | `ID!` | An entity id from §5.1. |
| `limit` | `Int` | Max labels (the web client uses `100`). |

Returns `data.node.organization.labels.nodes` — a list of `Label` (§7).

### 5.3 List messages (with pagination)

```graphql
query EntityMessagesQuery($entityId: ID!, $limit: Int, $offset: Int, $folder: EntityMessagesFolder, $search: String, $labelIds: [ID], $recipientIds: [Int]) {
  node(id: $entityId) {
    ... on Entity {
      id: dbId
      messages(limit: $limit, offset: $offset, folder: $folder, search: $search, labelIds: $labelIds, recipientIds: $recipientIds) {
        nodes {
          id: dbId
          summary
          statusText
          status
          type
          created
          sentAt
          pin
          public
          recipientsCount
          imagesCount
          videosCount
          audiosCount
          filesCount
          entity { id: dbId fullname disabled picture { uri id: dbId key } }
          user { id: dbId fullname }
          toEntity { id: dbId fullname status }
          label { id: dbId title color }
        }
        pageInfo { hasPreviousPage hasNextPage }
      }
    }
  }
}
```

Variables:

| Variable | Type | Required | Notes |
| --- | --- | --- | --- |
| `entityId` | `ID!` | yes | The inbox/entity to read. |
| `limit` | `Int` | no | Page size. Web client default: `25`. |
| `offset` | `Int` | no | Messages to skip (offset pagination). |
| `folder` | `EntityMessagesFolder` | no | Folder filter (§6.1). |
| `search` | `String` | no | Free-text search. |
| `labelIds` | `[ID]` | no | Restrict to messages having **any** of these labels. Send `[]` for none. |
| `recipientIds` | `[Int]` | no | Restrict to these recipients. |

Returns `data.node.messages.nodes` (list of `Message`, §7) and
`data.node.messages.pageInfo`.

**Pagination algorithm** (offset-based):

```
offset = initialOffset
loop:
  page = query(limit, offset, …)
  emit page.nodes
  if not page.pageInfo.hasNextPage or page.nodes is empty: stop
  offset += limit
```

### 5.4 Message detail — full body and attachments

```graphql
query MessageQuery($id: ID!) {
  node(id: $id) {
    ... on Message {
      id: dbId
      subject
      content
      summary
      statusText
      pin
      public
      recipientsCount
      created
      sentAt
      entity { id: dbId fullname disabled picture { uri id: dbId key } }
      user { id: dbId fullname }
      toEntity { id: dbId fullname status }
      label { id: dbId title color }
      tags { nodes { id: dbId name } }
      medias { nodes { id: dbId type uri original: uri(size: "w1280") filename key size thumbnail width height } }
      links
    }
  }
}
```

Variables:

| Variable | Type | Notes |
| --- | --- | --- |
| `id` | `ID!` | A message id (from §5.3). |

Returns `data.node` as a `MessageDetail` (§7), with `tags.nodes` and
`medias.nodes` flattened.

#### 5.4.1 Recipient view (what the web app uses)

The web app reads a received message **through the inbox entity**, and shows
the server-rendered HTML body (`rendered`) and any attached **reports**. Use
this form when the inbox entity is known; fall back to the form above if it is
rejected.

```graphql
query EntityMessageQuery($entityId: ID!, $id: ID!) {
  node(id: $entityId) {
    ... on Entity {
      id: dbId
      message(id: $id) {
        id: dbId
        subject
        content
        rendered
        summary
        # … same metadata, tags and medias fields as above …
        reports(limit: 40) {
          nodes {
            id: dbId
            name
            results(orderBy: { column: ID }, limit: 200) {
              nodes { reportFieldId entityId name type value }
            }
          }
        }
      }
    }
  }
}
```

Returns `data.node.message` as a `MessageDetail`.

- Show `rendered` (HTML) when non-empty, else `content`. A message whose body
  is only a report (e.g. a daily menu) has an empty `content`.
- Each report is a card of `name` / `value` rows (`Report`, §7). A report sent
  to many recipients can hold one set of results per recipient `entityId`:
  keep those of the inbox entity when present.
- Polls, forms, events and charges (payments) attached to a message are not
  part of this query. The list's counts (§10.2) tell which ones a message
  has; apps that don't support them should say so and point to the official
  app. An HTML-to-text conversion may turn embedded objects in `rendered`
  into U+FFFC (drawn as an "OBJ" box); drop those characters.

### 5.5 Change message status (read / unread / deleted)

Read and unread go through `updateRecipientInBatch`:

```graphql
mutation updateRecipientInBatch($input: updateRecipientInBatchInput!) {
  updateRecipientInBatch(input: $input) {
    clientMutationId
  }
}
```

`updateRecipientInBatchInput` fields (as sent by the web app):

| Field | Type | Notes |
| --- | --- | --- |
| `entityId` | Int | The inbox/entity the messages belong to. |
| `messagesId` | [Int] | Message ids to update. |
| `status` | `UpdateRecipientStatus` | `READ`, `AS_UNREAD` (also `ARCHIVED`, `UNARCHIVED`), §6.2. |
| `deleteNotification` | Boolean | The web app sends `true`. |
| `legacyMode` | Boolean | The web app sends `false`. |

```json
{ "input": { "entityId": 123456789, "messagesId": [987654321], "status": "READ", "deleteNotification": true, "legacyMode": false } }
```

Deleting goes through `createMessageStatusInBatch`:

```graphql
mutation createMessageStatusInBatch($input: CreateMessageStatusInBatchInput!) {
  createMessageStatusInBatch(input: $input) {
    clientMutationId
  }
}
```

`CreateMessageStatusInBatchInput` fields:

| Field | Type | Notes |
| --- | --- | --- |
| `entityId` | Int | The inbox/entity the messages belong to. |
| `messagesId` | [Int] | Message ids to update. |
| `status` | `MessageStatusEnum` | `DELETED` (or `ARCHIVED`), §6.2. **Not** `READ`/`UNREAD`: the server rejects them (§10.1). |

Deleting a message is `status = DELETED`. There is no separate delete mutation.
The web app undoes it with `deleteMessageStatusInBatch` (`recoverForMe`).

Example variables:

```json
{ "input": { "entityId": 123456789, "messagesId": [987654321], "status": "DELETED" } }
```

---

## 6. Enumerations

### 6.1 `EntityMessagesFolder` (message list `folder`)

| Value | Meaning |
| --- | --- |
| `UNREAD_BY_NTF` | Messages the logged-in **user** has not read — the per-user read state (§10.1). |
| `SCHEDULED` | Scheduled (not yet sent). |
| `SENT` | Sent. |
| `RECEIVED` | Received. |
| `DELETED` | Deleted. |

### 6.2 Message status

`UpdateRecipientStatus` (`updateRecipientInBatch.status`):

| Value | Meaning |
| --- | --- |
| `READ` | Mark read. |
| `AS_UNREAD` | Mark unread. |
| `ARCHIVED` / `UNARCHIVED` | Archive / move back to the inbox. |

`MessageStatusEnum` (`createMessageStatusInBatch.status`):

| Value | Meaning |
| --- | --- |
| `DELETED` | Delete for the current user. |
| `ARCHIVED` | Accepted by the schema; the web app does not use it here. |

### 6.3 Media type (`medias.nodes[].type`)

| Value | Meaning |
| --- | --- |
| `FILE` | Generic file. |
| `IMAGE` | Image. |
| `VIDEO` | Video. |
| `AUDIO` | Audio. |

### 6.4 `source` (in `sendCode`)

Enum; the web client sends `WEB`.

---

## 7. Data models

Field names are the response keys (after the `id: dbId` aliasing). Types are
described language-neutrally.

### Viewer

| Field | Type |
| --- | --- |
| `id` | int |
| `fullname` | string |
| `email` | string |
| `phone` | string |
| `language` | string |
| `created` | timestamp |
| `entities` | list of ViewerEntity (connection `nodes`) |

### ViewerEntity

| Field | Type |
| --- | --- |
| `id` | int (use as `entityId`) |
| `fullname` | string |
| `type` | string |
| `disabled` | bool |
| `organization` | Organization |

### Organization

| Field | Type |
| --- | --- |
| `id` | int |
| `fullname` | string |

### Message (list item)

| Field | Type |
| --- | --- |
| `id` | int |
| `summary` | string |
| `statusText` | string — `RECEIVED` / `READ` (with `status` `1` / `2`); per **entity**, not per user (§10.1) |
| `status` | int |
| `type` | string |
| `created` | timestamp |
| `sentAt` | timestamp |
| `pin` | bool |
| `public` | bool |
| `recipientsCount` | int |
| `imagesCount` | int |
| `videosCount` | int |
| `audiosCount` | int |
| `filesCount` | int |
| `unread` | integer `0`/`1` or `null` — undocumented and **not** the per-user read state, see §10.2 |
| `entity` | Entity (sender) |
| `user` | User (authoring account) |
| `toEntity` | ToEntity (**one** recipient — not necessarily the viewer's for multi-recipient messages; §10.1) |
| `label` | Label |

### MessageDetail

All of `Message`'s identity/metadata fields plus:

| Field | Type |
| --- | --- |
| `subject` | string |
| `content` | string (full body) |
| `tags` | list of Tag (connection `nodes`) |
| `medias` | list of Media (connection `nodes`) |
| `links` | list |

| `rendered` | string — server-rendered HTML body (§5.4.1); what the web app displays |
| `reports` | list of Report (connection `nodes`, §5.4.1) |

(`MessageDetail` does not include the per-type counts; use `medias` for
attachments.)

### Report

| Field | Type |
| --- | --- |
| `id` | int |
| `name` | string — card title |
| `results` | list of ReportResult (connection `nodes`) |

### ReportResult

| Field | Type |
| --- | --- |
| `reportFieldId` | int |
| `entityId` | int — recipient entity the answer belongs to |
| `name` | string — field label |
| `type` | string — `TEXT`, `SELECT`, `CHECK`, … |
| `value` | string, or a list of strings for `SELECT`/`CHECK`; the list may also arrive JSON-encoded in a string (the web app handles both) |

### Entity

| Field | Type |
| --- | --- |
| `id` | int |
| `fullname` | string |
| `disabled` | bool |
| `picture` | Picture (nullable) |

### User

| Field | Type |
| --- | --- |
| `id` | int |
| `fullname` | string |

### ToEntity

| Field | Type |
| --- | --- |
| `id` | int |
| `fullname` | string |
| `status` | int |

### Label

| Field | Type |
| --- | --- |
| `id` | int |
| `title` | string |
| `color` | string — bare hex **without** `#`, e.g. `f03e3e` (§10.1) |

### Tag

| Field | Type |
| --- | --- |
| `id` | int |
| `name` | string |

### Media

| Field | Type |
| --- | --- |
| `id` | int |
| `type` | MediaType (§6.3) |
| `uri` | string — the file; for images a low-res rendition. Takes an optional `size` argument (§10.4) |
| `original` | alias of `uri(size: "w1280")`: the 1280px-wide image the web app displays (§10.4) |
| `filename` | string |
| `key` | string |
| `size` | int (bytes) |
| `thumbnail` | string |
| `width` | int |
| `height` | int |

### Picture

| Field | Type |
| --- | --- |
| `id` | int |
| `uri` | string |
| `key` | string |

### AuthUser (from the authentication mutations)

| Field | Type |
| --- | --- |
| `id` | int |
| `language` | string |
| `isMaster` | bool |
| `hasPassword` | bool |
| `oauthProvider.accessToken` | string (bearer token) |
| `oauthProvider.refreshToken` | string |

---

## 8. End-to-end example

1. **Log in** (password, no OTP):

   `POST …/graphql` (no `Authorization`) with the `passwordAuthenticate`
   document and:
   ```json
   { "input": { "email": "user@example.com", "password": "•••" } }
   ```
   Read `data.passwordAuthenticate.user.oauthProvider.accessToken`.

2. **Identify entities:** call `ViewerQuery` with
   `Authorization: Bearer <accessToken>`. Pick `viewer.entities.nodes[i].id`
   as `entityId`.

3. **List labels:** `EntityLabelsQuery` with that `entityId`.

4. **List messages:** `EntityMessagesQuery` with `entityId`, `limit`, `offset`,
   optionally `labelIds`; paginate via `pageInfo.hasNextPage` + `offset += limit`.

5. **Read a message:** `MessageQuery` with the message `id`.

6. **Mark read:** `updateRecipientInBatch` with
   `{ entityId, messagesId: [id], status: "READ", deleteNotification: true, legacyMode: false }`.

---

## 9. Implementation checklist

- [ ] POST to the single endpoint with the three query params on every request.
- [ ] Send `Authorization: Bearer` on all calls **except** the auth mutations.
- [ ] Decode the `errors` envelope **before** checking the HTTP status; treat a
      non-empty `errors` as a first-class error regardless of status code.
- [ ] Alias `id: dbId` in documents; pass **numeric** ids to `node(id: …)`.
- [ ] Flatten connection `nodes` into lists.
- [ ] Implement offset pagination via `pageInfo.hasNextPage`.
- [ ] Support the OTP branch: `requiresOtp` → `sendCode` → `codeAuthenticate`.
- [ ] Never hard-code entity/label ids; discover them at runtime.
- [ ] Never hard-code credentials; take them from the caller.
- [ ] Take the user's read state from the `UNREAD_BY_NTF` folder, not from
      `statusText`, `unread` or `statuses` (§10).
- [ ] Decode loosely: `unread` is `0`/`1`/`null`, label `color` has no `#`, and
      fields beyond this document may change type (§10).

---

## 10. Observed behavior and undocumented fields

Findings from running clients against the live server, beyond what the web
client's documents above show. Each item says how it was established:
**live** = seen in real responses for a logged-in account; **schema** =
the field name passes query validation (see §10.3) but its values have not
been seen yet.

### 10.1 Value formats (live)

| Where | Observed | Notes |
| --- | --- | --- |
| `Message.statusText` / `status` (§5.3) | `"RECEIVED"` / `1`, `"READ"` / `2` | Not localized despite `locale=pt`. Does **not** match the logged-in user's read state: a message the official app shows as unread had `READ`/`2`, another `RECEIVED`/`1`. Likely the recipient entity's status (shared by all guardians of a student). Do not use it for the user's read/unread. |
| `Message.statuses` (§10.2) | `{"nodes": []}` | Empty on received messages, whether read or not. |
| `Message.toEntity` (§5.3, §5.4) | another family's student | For a message sent to many recipients, `toEntity` is **one** recipient, not necessarily the viewer's entity. Show the recipient groups (`tags`, §5.4) or the viewer's own inbox entity instead, with `recipientsCount` for how many received it. |
| Per-user read state | `folder: UNREAD_BY_NTF` (§6.1) | **Confirmed:** listing `EntityMessagesQuery` with `folder: UNREAD_BY_NTF` returns exactly the messages the official app shows as unread (blue dot) for the logged-in user. A message is unread iff it appears there; paginate it like any listing and collect the ids. |
| `createMessageStatusInBatch.status` (§5.5) | only `DELETED`, `ARCHIVED` | `READ` / `UNREAD` fail with `Expected type "MessageStatusEnum", found "READ"`. Read/unread use `updateRecipientInBatch` with `READ` / `AS_UNREAD` (validated against the live schema; `UNREAD` is rejected there too). |
| `MessageDetail.content` / `rendered` (§5.4.1) | both `null` for a report-only message; otherwise `rendered` equals `content` (HTML) | The official app shows the attached report instead. |
| `ReportResult` (§5.4.1) | `entityId: null`, `type: "CHECK"`, `value: ["…", "…"]` | A report that is the same for every recipient has `null` `entityId`. `CHECK` values arrive as JSON arrays (the web app also accepts a JSON-encoded string). |
| `Label.color` (§5.2, §5.3) | `"f03e3e"` | Bare 6-digit hex without a leading `#`. Prepend `#` before handing it to color parsers that require it. |

### 10.2 Undocumented `Message` fields

These are not requested by the documents in §5 but are accepted on
`Message` (they mirror the official app's list icons: unread dot, poll,
charge, report, form and event markers).

| Field | Type | Evidence | Meaning (inferred) |
| --- | --- | --- | --- |
| `unread` | int `0` / `1`, or `null` | live | **Not** the per-user read state: `null` on one message and `0` on another that the official app both show as unread. Meaning unknown. It is an integer, not a boolean: a strict boolean decoder fails on it. |
| `statuses` | connection of `MessageStatus` | live: empty for recipients | `MessageStatus` has `id`, `status`, `entityId`, `entity`, `userId`, `user`, `created`, `messageId` (no `type`/`name`); `statuses` accepts `limit` but not `entityId`. Returned **empty** on received messages (read or unread), so it cannot give the recipient's read state; probably only populated for the sender. |
| `recipients`, `notifications`, `logs` | connections | schema | Not explored. `Notification` has `created` (no `status`/`unread`). |
| `surveysCount` | int (assumed) | schema | Polls attached to the message. |
| `chargesCount` | int (assumed) | schema | Charges (payments) attached. |
| `reportsCount` | int (assumed) | schema | Reports attached. |
| `formsCount` | int (assumed) | schema | Forms attached. |
| `commitmentsCount` | int (assumed) | schema | Events/commitments attached. |
| `surveys`, `charges`, `reports`, `forms`, `commitments` | connections (need a sub-selection) | schema | The attached items themselves; shape not explored. |
| `fwMessageId` | ? | schema | Probably the id of a forwarded original. |
| `deleted` | ? | schema | Probably the per-user deleted flag. |

Names that do **not** exist on `Message` (validation rejects them): `seen`,
`isRead`, `read`, `readAt`, `isUnread`, `readStatus`, `userStatus`, `viewed`,
`viewedAt`, `visualized`, `isViewed`, `readByUser`, `isReadByEntity`,
`messageStatuses`, `recipient`, `statusByEntity`, `entityMessageStatus`,
`unreadByEntity`, `notification`, `lastReadAt`, `readCount`, `seenAt`,
`isEvent`, `hasAttachment`, `original`, `priority`, `events`, `replies`,
`starred`, `archived`.

Because these fields are undocumented, a robust client should request them
in a separate "rich" variant of `EntityMessagesQuery` and fall back to the
documented query if the server rejects them (GraphQL error) or their values
do not decode — never let an optional field break the message list. The
Android app (`android/`) does exactly that.

### 10.3 Probing the schema

Introspection is disabled (`"GraphQL introspection is disabled"`, HTTP
400), but queries are **validated before authorization**, so field names
can be checked without a token by querying `node(id: 1) { ... on Message
{ <field> } }`:

| Response | Meaning |
| --- | --- |
| HTTP 200, `errors: [{"message": "Not authorized"}]`, `data.node = null` | The field **exists** (validation passed; only authorization failed). |
| HTTP 400, `Cannot query field "x" on type "Message".` | The field does not exist. |
| HTTP 400, `Invalid request` | The field does not exist either; the server masks GraphQL's "Did you mean …?" suggestions behind this generic message. |

Object/connection fields need a sub-selection (e.g. `surveys { __typename }`)
or they fail validation.

### 10.4 Media renditions and web links

`Media.uri` and `Media.thumbnail` take an optional `size: String` argument
that selects a resized rendition (schema: a string is accepted, an int is
rejected with `Expected type "String"`). The web client never displays plain
`uri` for images: it shows `uri(size: "w1280")` (aliased `original`). Other
values it sends: `uri`: `w640`, `w360`, `w1240`, `s70`; `thumbnail`: `w320`,
`w480`.

Live (one image message): for an `IMAGE`, plain `uri` is a **low-res**
rendition, far blurrier than in the official app, both shown inline and
opened in an external viewer; `original` is sharp. Despite the web client
aliasing plain `uri` as `fullUri`, it is not the full-size file for images.
Apps should both display and open `original` for images, falling back to
`uri`. Non-image types keep plain `uri`.

A message's page in the web app (read from the web client's routes, not yet
live-verified) is `https://classapp.com.br/entities/{entityId}/messages/{messageId}`,
where `entityId` is the inbox entity reading it and both ids are `dbId`s.

---

*This is the protocol the apps in this repository implement; the Android
client (`android/app/src/main/java/.../api/`) is a reference implementation.*
