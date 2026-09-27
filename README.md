# ClassApp Triage

Apps that sort a [ClassApp](https://www.classapp.com.br) school-communication
inbox into **Important** and **Routine** messages using editable rules. They
can then mark routine messages as read or delete them in bulk, so only what
matters is left.

> **Unofficial.** This project is not affiliated with, endorsed by or
> supported by ClassApp. It uses the same undocumented GraphQL endpoint as
> the ClassApp web app (reverse-engineered, see [`docs/API.md`](docs/API.md)),
> which can change or break at any time. Use at your own risk, with your own
> account.

## Platforms

| Platform | Status | Source |
| --- | --- | --- |
| Android 8.0+ | Available | [`android/`](android) ([README](android/README.md)) |
| iOS | Planned | — |

## Privacy

You log in with your own ClassApp credentials. The apps talk only to ClassApp's
servers and the hosts that serve message attachments. Only the session token
is stored, encrypted on the device; the apps never save the password. There is
no analytics or tracking.

## Docs

- [`docs/API.md`](docs/API.md): the ClassApp web API as used by the apps.
- [`docs/TRIAGE.md`](docs/TRIAGE.md): rule semantics and the portable JSON
  rules format (rules files can be moved between platforms).

## License

[MIT](LICENSE)
