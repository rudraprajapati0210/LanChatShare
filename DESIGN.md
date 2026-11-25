# LanChatShare

Design source of truth for the native Compose app. Token values live in [design/tokens.json](design/tokens.json). All illustrations, fonts and icons ship in the APK.

## Colour and type

The reading surface is warm ivory. Deep teal anchors the Share action, outgoing bubbles and selected navigation. Incoming bubbles use a slightly darker neutral surface. Dark mode reverses the surface hierarchy and uses mint for actions. Use paired foreground/background roles throughout. Wallpaper colour is opt-in. Keep launcher artwork in brand colours.

Bundle DM Sans under its font licence for UI and wordmark, with Android fallback for other scripts and emoji. Body copy uses 16/24 sp. Message metadata uses 12/16 sp. Use tabular digits for byte counts and pairing codes. Headlines wrap before actions lose their labels.

## Navigation and layout

After the one-field welcome, Chats is home. Every signed-in destination keeps a labelled Share files action in the top bar. Inside Share it returns to the picker while preserving the transfer queue. Android Back returns to the originating destination. Compact windows use a bottom bar. At 600 dp use a rail, at 840 dp add a conversation detail pane if both panes fit at the current font scale. Keep start/end alignment throughout.

## Chats

Use a flat conversation list with an initials avatar, name, last message, time and unread count. Give pinned conversations a small pin and an accessible label. Incoming bubbles align to start and outgoing bubbles to end. Collapse touching corners of adjacent messages by the same author, put a small tail on the last bubble and reserve the timestamp row until requested. At most 80% width on phones and 480 dp on tablets. Replies show author and excerpt. Reactions have text equivalents. Keep the scroll anchor when media loads, history prepends or the keyboard opens. New arrivals scroll into view only when already at the newest message.

## Share

Background controls live in Settings under Notifications and reachability. Put the explanation next to the control. Show one alert per conversation, with the peer's name and avatar. Offer Reply and Mark as read directly. Keep transfer progress quiet and file requests actionable. Diagnostics describes observed device states in plain language. The first active transfer can surface the optional battery guidance once.

Lay out the flow in reading order. Start with a file picker, follow with an editable selection summary, then recipients by name and one Send action. A broad recipient shape contracts into a progress indicator after acceptance. Show verified bytes and total beside progress. Pause, resume and retry retain the recipient and selection. Queue and completed rows stay still. “Waiting for Rudra” and “Rudra declined” identify the person and state. A static bar substitutes for the wave under reduced motion.

## Nearby, Library and Settings

Nearby groups devices by availability, shows trust separately and appends a short ID suffix only for ambiguous labels. Pairing displays six digits grouped in threes, peer name, Match and Cancel. Make diagnostic results individually readable. Library starts with type filters above a date-ordered grid or list. Open a file from its row and expose direction, size and sender in details. Settings uses labelled rows for Profile, Network and hotspot, Privacy and security, Storage, Appearance and About.

## Interaction and accessibility

Use the system motion scale. Reserve spring motion for direct selection and Share progress transitions. Keep navigation and focus order identical with animations disabled. Use haptics on confirmation. Swipes have equivalent menu actions, and Nearby has a labelled refresh action. At 200% text size stack secondary actions and allow full-width bubbles. Use 48 dp targets, visible keyboard focus, TalkBack headings, inline field errors and distinct text for every status. Announce transfer state changes and provide percentage on focus. Test normal text at 4.5:1 and controls at 3:1 in both themes.

An empty chat list shows an original line drawing of two nearby phones with “Start your first chat” and a Find nearby devices action. A disconnected transfer preserves its progress and offers “Try again” after the network returns.

## Brand checkpoint

Back & forth (A) is the approved identity. The two offset speech shapes form the launcher foreground and themed monochrome layer. Use the same silhouette beside the LanChatShare wordmark and as the starting shape for Share transitions. Keep the design clear at 24 dp and within the adaptive icon safe zone. The [concept board](design/logo-directions.svg) preserves the original alternatives.
