package com.rrgmc.classapptriage.api

// GraphQL operations used by the client, as documented in docs/API.md §5.
// Keep them in sync with that document.

internal const val QUERY_VIEWER = """
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
        organization {
          id: dbId
          fullname
        }
      }
    }
  }
}"""

internal const val QUERY_ENTITY_MESSAGES = """
query EntityMessagesQuery(${'$'}entityId: ID!, ${'$'}limit: Int, ${'$'}offset: Int, ${'$'}folder: EntityMessagesFolder, ${'$'}search: String, ${'$'}labelIds: [ID], ${'$'}recipientIds: [Int]) {
  node(id: ${'$'}entityId) {
    ... on Entity {
      id: dbId
      messages(limit: ${'$'}limit, offset: ${'$'}offset, folder: ${'$'}folder, search: ${'$'}search, labelIds: ${'$'}labelIds, recipientIds: ${'$'}recipientIds) {
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
}"""

/**
 * [QUERY_ENTITY_MESSAGES] plus fields the official app uses for its row icons
 * and unread marker. They are undocumented (docs/API.md §10.2, found by probing the schema),
 * so the client falls back to the documented query if the server rejects them.
 */
internal val QUERY_ENTITY_MESSAGES_RICH = QUERY_ENTITY_MESSAGES.replace(
    "          filesCount\n",
    "          filesCount\n          unread\n          surveysCount\n          chargesCount\n" +
        "          reportsCount\n          formsCount\n          commitmentsCount\n",
)

internal const val QUERY_MESSAGE = """
query MessageQuery(${'$'}id: ID!) {
  node(id: ${'$'}id) {
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
}"""

/**
 * The message as the official web app reads it for a recipient: through the
 * inbox entity, with the server-rendered HTML body and attached reports
 * (docs/API.md §5.4). Falls back to [QUERY_MESSAGE] if rejected.
 */
internal const val QUERY_ENTITY_MESSAGE = """
query EntityMessageQuery(${'$'}entityId: ID!, ${'$'}id: ID!) {
  node(id: ${'$'}entityId) {
    ... on Entity {
      id: dbId
      message(id: ${'$'}id) {
        id: dbId
        subject
        content
        rendered
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
        tags(limit: 40) { nodes { id: dbId name } }
        medias { nodes { id: dbId type uri original: uri(size: "w1280") filename key size thumbnail width height } }
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
}"""

internal const val QUERY_ENTITY_LABELS = """
query EntityLabelsQuery(${'$'}entityId: ID!, ${'$'}limit: Int) {
  node(id: ${'$'}entityId) {
    ... on Entity {
      id: dbId
      organization {
        id: dbId
        labels(limit: ${'$'}limit) {
          nodes { id: dbId title color }
        }
      }
    }
  }
}"""

internal const val MUTATION_CREATE_MESSAGE_STATUS_IN_BATCH = """
mutation createMessageStatusInBatch(${'$'}input: CreateMessageStatusInBatchInput!) {
  createMessageStatusInBatch(input: ${'$'}input) {
    clientMutationId
  }
}"""

internal const val MUTATION_UPDATE_RECIPIENT_IN_BATCH = """
mutation updateRecipientInBatch(${'$'}input: updateRecipientInBatchInput!) {
  updateRecipientInBatch(input: ${'$'}input) {
    clientMutationId
  }
}"""

internal const val MUTATION_PASSWORD_AUTHENTICATE = """
mutation passwordAuthenticate(${'$'}input: PasswordAuthenticateInput!) {
  passwordAuthenticate(input: ${'$'}input) {
    requiresOtp
    user {
      id: dbId
      language
      isMaster
      hasPassword
      oauthProvider {
        accessToken
        refreshToken
      }
    }
  }
}"""

internal const val MUTATION_SEND_CODE = """
mutation sendCode(${'$'}email: String, ${'$'}phone: String) {
  sendCode(input: {email: ${'$'}email, phone: ${'$'}phone, invite: true, source: WEB, isNewCode: true}) {
    __typename
  }
}"""

internal const val MUTATION_CODE_AUTHENTICATE = """
mutation codeAuthenticate(${'$'}input: CodeAuthenticateInput!) {
  codeAuthenticate(input: ${'$'}input) {
    user {
      id: dbId
      language
      isMaster
      hasPassword
      oauthProvider {
        accessToken
        refreshToken
      }
    }
  }
}"""
