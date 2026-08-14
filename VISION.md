# Village Mail: Vision

A postal system for Minecraft that feels like it was always there.

## What It Isn't

- Not a storage system. One item slot. Not an ender chest workaround.
- Not a logistics mod. Mail doesn't need to be "carried" or have delivery time.
- Not a communication platform. Simple letters, simple UI.

## The Experience

You find a village. There's a post office with a public mailbox. A mail person villager offers to sell you a mailbox for 5 emeralds and will buy your paper. You place your mailbox near the village. Every few minutes, the mail person visits, sometimes with a small gift, sometimes just making rounds. You walk to the public mailbox, write a letter to a friend, attach a diamond, send it. They open their mailbox and find it waiting.

One morning a letter arrives from the village elder, asking for help with a problem nearby. Another day, donated materials go toward a new building in the village square. These moments come from other mods (Village Quests, Village Builder) but they arrive through the same mailbox. The mod stands alone. The integrations deepen it.

## Done

- A mailbox block, purchasable from the mail person. Not craftable.
- An inbox UI: list of messages, read/unread.
- A compose UI: pick recipient, write a short message. Attach one item at the public mailbox.
- A message detail UI: read the letter, collect the attached item, reply.
- Mail person villager profession, workstation at the public mailbox.
- Mail person trades: sells mailboxes, buys paper.
- Post office and public mailbox in the village structure pool.
- Mail person visits player mailboxes approximately every 5 real-time minutes (with a random gate). Occasional small gifts.
- Village Quests integration: quest offers by mail, reputation from donations (when sibling mod is present).
- Village Builder integration: post office structures, material donations (when sibling mod is present).
- Persistent storage with auto-save. Survives crashes.

When it's done, mail feels like a village feature that Mojang forgot to add.

## Principles

These are ordered. When two conflict, the higher one wins.

1. **Vanilla feel.** If it wouldn't look right next to a crafting table UI, simplify it.
2. **One item, not five.** The attachment slot is for a gift, not a shipment.
3. **Messages are the feature.** The mailbox opens a UI. It doesn't hold physical items.
4. **The mail person is ambient.** Visits happen. Gifts are occasional. Presence, not mechanics.
5. **Standalone first.** Every integration is a soft dependency.
6. **Simple scales.** Simple data model, simple networking, simple UI.

## Constraints

- Targets the Minecraft, Fabric Loader, and Java versions declared in this mod's `gradle.properties` and `fabric.mod.json`.
- Small SMP is the target audience.
- All UI is driven through Pandorical. Pandorical is a required client-side dependency for actually using the mail system; there is no vanilla-client fallback beyond a chat message telling the player it's required.
