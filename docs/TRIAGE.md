# Triage rules

Specification of the rule engine that splits an inbox into **Important** and
**Routine** messages, and of the portable JSON rules format. Every app in this
repository must follow it so that rules files work the same on every platform.
Reference implementation: `android/app/src/main/java/.../triage/Triage.kt`.

## Classification

A config has two ordered rule lists, `important` and `routine`.

1. The first matching `important` rule makes the message **Important**.
2. Otherwise, the first matching `routine` rule makes it **Routine**.
3. Otherwise, the message is **Important**, so nothing unknown is hidden.

So a message is Routine only when a routine rule matches and no important rule
does. The result carries the name of the matching rule, or no rule when rule 3
applied.

## Rule matching

A rule has a `name` and up to six matcher fields. Each field is a list of values:

| Field | Type | Matches when |
| --- | --- | --- |
| `labels` | strings | the message label title **equals** any value |
| `labelIds` | numbers | the message label id is any value |
| `senders` | strings | the sender (entity) name **contains** any value |
| `senderIds` | numbers | the sender entity id is any value |
| `summaryPrefixes` | strings | the summary **starts with** any value |
| `summaryContains` | strings | the summary **contains** any value |

- Non-empty fields are ANDed together, and the values within a field are ORed.
- A rule whose fields are all empty never matches.
- A message with no label has label title `""` and label id `0`. A message with
  no sender has name `""` and id `0`.
- Text comparisons are **case- and accent-insensitive**. Both sides are
  trimmed, lowercased (locale-independent), and these accented characters are
  mapped to their base letter: `á à â ã ä` → `a`, `é è ê ë` → `e`,
  `í ì î ï` → `i`, `ó ò ô õ ö` → `o`, `ú ù û ü` → `u`, `ç` → `c`, `ñ` → `n`.

## JSON format

```json
{
  "important": [
    { "name": "marked urgent", "summaryContains": ["urgente", "importante", "atenção"] }
  ],
  "routine": [
    { "name": "meals", "labels": ["Alimentação"] },
    { "name": "menu", "summaryContains": ["cardápio"] },
    { "name": "daily routine", "summaryPrefixes": ["Rotina", "Relatório"] }
  ]
}
```

- `important`, `routine` and each rule's `name` are always written.
- Empty matcher lists are omitted when writing and default to empty when reading.
- Unknown keys are rejected on import, so a typo can't silently drop a rule.

## Default rules

The JSON above is the default config. Apps use it when no rules have been
saved, and restore it on "reset to defaults".
