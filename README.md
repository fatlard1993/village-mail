# Village Mail

A postal system for Minecraft villages that feels like it was always part of the game. Buy a mailbox from the village's Mail Person, write letters to other players with an optional item attachment, and check for deliveries: gifts and visits from the Mail Person, gameplay updates from companion mods, and even villager "obituaries" when a villager in your village dies.

## Important: This mod requires Pandorical on the client

Village Mail's entire UI (mailbox list, message detail view, compose screen, and public mailbox screen) is built and driven through Pandorical's `screens()` API. **A player without Pandorical installed cannot use the mail system.** Opening a mailbox or trying to compose mail without Pandorical produces a chat error message instead of a working screen. This is a deliberate design choice; see [VISION.md](VISION.md) for the reasoning. If you want Village Mail to work for your players, they need Pandorical installed.

## Features

- **Personal mailbox**: purchasable from a Mail Person villager (not craftable); shows your unread and read messages, lets you compose new mail
- **Public mailbox**: a workstation block found at village post offices; write a letter to any online player or known mailbox owner and attach a single item
- **Mail Person villager profession**: works the public mailbox, sells mailboxes, buys paper, and periodically visits players' personal mailboxes (roughly every 5 real-time minutes) with occasional small gifts
- **Message replies, forwards, and item collection**: read a letter, collect its attachment, reply, or forward it from the message detail screen
- **Village donations**: when village-builder is installed, the public mailbox's recipient list includes a "Village donation" entry; send it an attached item to donate materials toward village construction, and a short delayed thank-you letter arrives from "The Village"
- **Villager obituaries**: when a villager in a tracked village dies, nearby mailbox owners receive a short delayed letter about it
- **Unread-mail HUD badge**: a small "X unread" indicator appears in the corner of the screen whenever you have unread mail, updating live as messages arrive or are read
- **Persistent, auto-saving storage**: mail and mailbox ownership survive server restarts and crashes
- **Post office and public mailbox structures**: added to the village structure pool during world generation
- **Optional integration with village-quests**: quest offers and reputation-driven consequences can arrive by mail when village-quests is installed
- **Optional integration with village-builder**: post office structures register into village-builder's build pools, and construction milestones can notify mailbox-owning players, when village-builder is installed

## Pandorical

Village Mail uses Pandorical for two things:

- **`screens()`**: builds and drives all four mail UI screens (the mailbox list, message detail view, compose screen, and public mailbox screen) entirely server-side.
- **`hud()`**: drives a small unread-mail count badge shown in the corner of the screen whenever a player has unread messages.

Pandorical must be installed client-side for any of this to work. Without it, a player gets a chat message telling them Pandorical is required instead of a functioning mailbox; see the note above.

## Development

Installing is in [DEVELOPMENT.md](DEVELOPMENT.md).

## License

MIT, see [LICENSE](LICENSE).
