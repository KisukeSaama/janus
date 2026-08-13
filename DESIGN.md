# Design System

## Identity

What Janus holds is a **register**: a place where authorizations are recorded, read back, and revoked. Every row is a statement about what one machine may do to another. What it is worked in is a console with a rail, addressed like any other operational tool: the register is the content, not an argument for inventing a navigation of its own.

Three consequences drive every decision below.

1. The interface is read more than it is clicked. Legibility of dense textual data outranks visual interest.
2. Machine data (slugs, paths, methods, identifiers, keys) is set in a monospace, because a path you can misread is a path you can misconfigure. Prose and machine data are two textures, and the difference between them is information.
3. Orange is the product. It marks what acts: primary actions, selection, focus. Nothing else is coloured for decoration.

## Theme

**Dark by default, light as a setting.** The console sits beside a terminal and an editor, and the accent was drawn against a charcoal ground. Anyone reading in a bright room switches in the settings menu; the choice is stored in `localStorage` and applied by an inline script before first paint, so the page never flashes the wrong ground.

Both themes carry the same orange. Nothing is dark-only.

## Color

Restrained strategy: near-achromatic neutrals plus one accent.

Neutrals are tinted **cool** (hue 268, chroma 0.002 to 0.014), not toward the brand hue. Warm greys under an orange accent read as brown and dull the very colour they are meant to support. The ground is ink rather than office grey, which is what keeps the orange hot instead of merely present.

| Role | Dark | Light |
|---|---|---|
| `canvas` | `oklch(0.158 0.008 268)` | `oklch(0.97 0.004 268)` |
| `surface` | `oklch(0.203 0.009 268)` | `oklch(0.998 0.002 268)` |
| `sunk` | `oklch(0.246 0.01 268)` | `oklch(0.945 0.006 268)` |
| `line` | `oklch(0.3 0.011 268)` | `oklch(0.892 0.008 268)` |
| `line-strong` | `oklch(0.505 0.014 268)` | `oklch(0.66 0.012 268)` |
| `text` | `oklch(0.963 0.003 268)` | `oklch(0.225 0.012 268)` |
| `text-2` | `oklch(0.79 0.008 268)` | `oklch(0.44 0.012 268)` |
| `text-3` | `oklch(0.672 0.01 268)` | `oklch(0.528 0.013 268)` |

No pure black, no pure white.

The two rules are not interchangeable. `line` separates: table rows, panel edges, dividers, anything decorative. `line-strong` is the border of a control you can operate, and it is held at 3:1 against the surface behind it so inputs and secondary buttons satisfy WCAG 1.4.11. Using `line-strong` to draw a read-only value makes it look clickable, and using `line` on a field makes the field disappear.

### The accent

A true orange at hue 45, not an amber: `oklch(0.72 0.19 45)` dark, `oklch(0.685 0.2 45)` light.

It splits in two, because an orange bright enough to be a surface is never dark enough to be body text on white:

- `accent` fills buttons, active tabs, and badges. Text on it is `on-accent`, a near-black orange.
- `accent-text` is the readable tone for links, icons, and numerals on a neutral ground. In light it drops to `oklch(0.492 0.165 40)` to clear 4.5:1.

Semantic colours never use hue 45, so nothing competes with the accent: `ok` at 152, `warn` at 85 to 100, `bad` at 18 to 22. Dark washes stay near-achromatic, because a dark yellow is olive and olive is not a status; the border and the icon carry the signal.

## One instance, one environment

Janus is deployed once per environment, each instance with its own database and its own OpenBao. So the console has no environment switch, no environment column, and no environment field on any form: which environment you are looking at is which address you opened. A discriminator that only ever holds one value per deployment is not information, it is a field to get wrong — and getting it wrong meant writing a production secret from a page that looked like development.

## Typography

**Two families, one job each.** Both self-hosted through Fontsource; no network font dependency, no serif, no display face.

- **Instrument Sans Variable** carries headings, labels, buttons, prose, and every figure that appears inside a sentence. A grotesque with some tension in it: narrower and more current than a corporate workhorse, still boring enough to read all day at 13px.
- **Spline Sans Mono Variable** carries everything the gateway will match literally — slugs, paths, methods, identifiers, keys, timestamps, `curl`. Humanist and warm, not a terminal impression.

The split is the point. A console that sets `payments-api-v2` in the same face as "Store a secret" is asking the reader to work out which of the two is a value, every time. Setting machine data as machine data is what a developer reads as competence, and it costs one class: `.data` applies the monospace, `tabular-nums`, and `slashed-zero`, so columns align and 0 never reads as O. `.num` stays in the sans, for figures inside running prose.

Fixed rem scale, ratio near 1.2 at the small end and wider at the top: `11 / 12 / 13 / 14 / 16 / 21 / 26px`. The steps below 16px are roles (stamp, hint, body, data) rather than hierarchy; hierarchy is carried by weight and colour.

Instrument Sans sets a touch tight at display sizes and a touch loose in small caps, so two tokens carry the correction and no component invents its own em value: `tracking-title` at `-0.019em` for anything 16px and up, `tracking-stamp` at `0.085em` for the 11px uppercase stamps.

## Components

- Radius `5px` on controls, `8px` on panels.
- Inputs sit on `sunk`, one step below the panel they live in, and rise to `surface` on focus. A field is a hole in the page, not a rectangle drawn on it.
- Primary action: filled accent. Secondary: surface with a rule. Quiet: text only. Destructive: `bad` text with a matching hairline, and never without being confirmed first.
- Elevation comes from hairlines. One shadow level, reserved for overlays.
- Focus is a 2px accent outline at 2px offset, never a colour that could read as a status.
- Targets are 38px high, and `@media (pointer: coarse)` lifts them to 44px. The breakpoint is the input device, not the viewport.
- Every control ships default, hover, focus-visible, active, disabled, and where relevant loading and error.
- No pill shapes. Status is a rectangular stamp or a word beside a small square marker.
- A press translates the control half a pixel: enough to feel, too little to notice. Removed under `prefers-reduced-motion`.
- **Selected states never change font weight.** Bolding the current tab widens it and shoves its neighbours sideways on every switch. The accent fill, the 2px rule, and the text colour say it without moving anything.
- Segmented controls (the outcome filter) are sized to land on the same baseline as the buttons beside them, and lift to a thumb-sized target under `pointer: coarse` like every other control.
- Rail destinations are marked by fill, not by weight or by a coloured stripe down their edge: the current one takes the `sunk` ground and an `accent-text` icon, hover takes a 55% wash of the same fill.
- **Consequential actions are confirmed in a dialog, and there is only one of them.** `ConfirmDialog` takes the middle of the window, dims the page behind it, and states what is about to happen, to which record, and what will be true afterwards. Every confirmation in the console arrives on that surface, whether it was raised from a row, from a record, from a menu, or from inside the setup flow: a question asked four different ways is four things to learn, and a note beside a button is easy to answer without having read it. Confirmations were previously armed in place, anchored to their trigger; nothing in the console does that any more.
- Two details of the dialog are load-bearing. A destructive one opens with focus on the way out, so the key that dismisses it is never the key that fires it. And it is sealed while its request is in flight, because a confirmation dismissed mid-write leaves the reader with no idea whether the write happened.
- **A row shows one verb.** Past two, the rest go under a `⋯` menu and the row keeps only what the reader came to do. A register is read far more often than it is clicked, and four buttons on every line are four lines of noise for the rows nobody is acting on. The actions column takes exactly what its controls measure and never wraps: given a column that grows, it will otherwise collapse and stack its buttons four high.
- **Two actions with the same word are two different actions.** The menu names the authority each entry acts under — your account, the whole deployment — because `Delete` beside `Delete` was a personal credential beside every account's access to an API.

## The unit of work

Janus stores four records to authorize one call: an application, a provider, a credential, and a grant. That is the security model, not a task. Nobody maintains four records; they maintain one statement, "this service may call that API", and they want to know whether it currently works.

The console calls that statement a **connection**. It is assembled on the client from the grant and the three records it points at, owns no state of its own, and every edit still goes through the ordinary endpoints, so the registry stays the single source of truth. Three consequences:

- **The connections are the home screen.** One row per statement: who calls what, with which secret, at which gateway address, and whether calls are being forwarded right now. `Live`, `Paused`, and `Not forwarding` are three different states, and the third is the one four separate tables cannot show: a grant that reads active while its provider is disabled. The list is a section of the dashboard rather than a destination of its own, under a head that carries how many of them are live.
- **A connection has a page.** How to call it, where it goes, how often, which personal credential it presents, how old the caller's key is, and how to stop it. The global destination and traffic policy are editable there only for administrators; every account can manage its own credential and grant. It is the only screen that reads all four `enabled` flags in gateway order, so it names the record that would refuse the call instead of saying "inactive".
- **The registry keeps two collections.** Applications remain personal machine identities. APIs form one searchable, paginated catalogue shared by the deployment: administrators define destinations, authentication strategies, cache policy and limits; each account activates catalogue entries and provisions its own credentials. Grants subscribe that account's applications to its activations.

Vocabulary follows: applications are **applications** — what calls the gateway — and the provider-plus-credential pair is an **API**. Grants have no name in the console at all: they are the connection.

## Connecting an API

Setup takes the whole window for the length of one task. It is the reason the console exists, and a 480px rail is the wrong shape for a decision taken in three steps; everything else stays in the side panel.

Administrators can register an API and its authentication contract, then provision their own credential. Ordinary users enter through the catalogue: search, activate, and provide only the credential fields required by the administrator-selected strategy.

**It opens directly on the API description.** The operator names the API and its destination, then declares how it authenticates. The two-step flow stays focused on the contract Janus will enforce and does not put a preset catalogue between the operator and the form.

**The strategies are named for what you hold, not for how it travels.** Somebody arriving has a thing in their hand — a key, a client id and secret, nothing at all — and not an opinion about whether it belongs in a header. "Connect an account" is the entry that says what it is for rather than what it does, because `OAUTH2_AUTHORIZATION_CODE` describes a mechanism, and what separates it from the entry above it is not the mechanism: it is that one reads the Spotify catalogue and the other reads somebody's playlists.

The list is short on purpose. An assertion signed with a private key and a key split across two headers were both written and then removed: each strategy on that screen is one more thing between a reader and the one they need, and neither of those is met outside enterprise service accounts.

**Consent is a state, not a failure.** A connection nobody has authorised yet is not "disabled" — it is waiting for a person, which is a thing an operator can act on and an error message is not. So it has its own block on the connection page, with the one button that changes it, and the redirect address to declare at the provider is shown there while it is still useful rather than left in the documentation to be discovered after the first refusal.

**Registering is not activating.** The flow writes the catalogue entry, which belongs to the deployment; what makes an API active for somebody is the credential their own account holds for it. The step that would provision the administrator's alongside it is one unticked box, so an entry written on behalf of everyone does not silently make its author a caller of it. Ticked, the secret is asked for and both records are created in the same unwound-on-failure order as before. The public path is global — `/gateway/{slug}` — because the application key and grant already identify who may call it.

The four records are created in dependency order and unwound in reverse if a later one is refused, so a failed attempt never leaves half a connection behind.

It ends on the screen a developer actually needed: the key, the identifier, a `curl` that works as pasted, and a button that sends one real request through the gateway with the key just issued. A wizard that ends on "created successfully" leaves the hardest question unanswered. This one answers it, and separates the two failures that look identical from outside: Janus refusing, and the API refusing.

A first run shows one paragraph and one button, not four empty tables.

## Documentation

The console has two readers. Everything above is written for whoever registers a connection; the guide is written for whoever writes the service that will use it, and that person arrives holding a key with four questions: what do I send, what may I call, what comes back, and who refused when it fails.

It answers them on one page. A guide split into eight is a guide nobody finishes, and one page is short enough that the contents list beside it is a way back to a question rather than a substitute for reading. The order is the order an integration happens in: the principle, the three values, one request that proves the connection works, that request in five runtimes, what Janus already does on the caller's behalf, what each refusal means, and what keeps a key revocable.

Its examples are assembled from a connection that exists in this deployment — the address the console is served from, that connection's gateway path, its application id — so a snippet is copied and run rather than adapted. The one value never printed there is the API key: it is shown once, on the screen that issues it, and a page anybody can open is not that screen.

Prose and machine data meet inside the same sentence more often here than anywhere else in the console, so the dictionary marks the literal parts and the page sets them in the monospace. A status, a header, and a path pattern are not words.

## The dashboard

The home screen is one page read top to bottom the way a day is: what is wrong, what is about to be, what exists, what just happened. `Needs attention`, `Coming due`, the connections, the last decisions from the log. Every section reads records the page already holds, so the whole thing costs the four requests the connections cost on their own.

It is called the dashboard rather than the connections because three of those four sections are not the inventory, and a destination named after its longest table is a destination nobody reads the top of.

### Needs attention

The console leads with the work, not the inventory. Four checks are computed from data already on screen: connections that look active and forward nothing, keys older than 90 days, services holding a key they never use, and stored secrets no connection references.

Each finding states what is wrong, what to do, and names the records concerned. It also carries their identifiers, so `Review` filters the list down to exactly those rows rather than returning the reader to a full table. When nothing is wrong, it says so, quietly.

### Coming due

The deadlines close enough to be work, soonest first, with whatever has already lapsed at the top. Each line is the secret, the API it belongs to, and the recorded date beside how far off it is. Past six the rest are a count and the whole register is one click away, because a section that quietly showed the first six would read as the whole list.

It is a section and not a fifth finding: `two secrets are reaching the dates recorded for them` is the same sentence with the names, the dates and the order taken out. A count is what you write when you have nowhere to put the records; here there is somewhere.

**An expiry date is shown twice, and stated once.** A key issued by another company announces its own end nowhere, so storing a secret records the date it stops working, and Janus is what remembers it. The APIs table shows that date beside how far off it is — the date alone is a fact nobody converts in their head, "in 5 days" alone is a claim you cannot check — and the tone appears only once the deadline is near enough to be work. A key due in nine months is drawn as quietly as one with no date at all.

The announcement itself is made once per stage: quietly at thirty days, insistently at seven, then on the day. Not every morning until someone acts, because a notice that repeats is a notice that gets filtered. It reaches the notification menu in the top bar and, for whoever has not opened the console, one mail per sweep rather than one per key. The menu is deliberately not cleared by being looked at: a badge that disappears on a glance is how a key expires anyway. Marking read and dismissing are both things you choose to do.

The menu and `Coming due` are not the same list, which is why the menu with nothing in it says `No notifications` rather than naming a window of days. An announcement is an event, raised once per stage and answered by being read. A deadline is a state, true until somebody stores a new date. Dismissing the announcement cannot move the date, so dismissing it does not take the secret off the dashboard.

Key age is why `apiKeyRotatedAt` exists in the schema: creation time answers "when was this registered", not "how old is the key in circulation", and the two diverge the first time a key is rotated. The services table shows key age instead of registration date, marks anything past the threshold, and raises its `Rotate key` button from quiet to outlined so the recommended action looks like one.

## Layout

**A rail, a bar, and one page under them.** The rail is 256px, fixed from the top of the window to the bottom, and it names all seven destinations at once: what an operator does (the dashboard, activity) above what Janus stores (services, APIs, secrets, access rules), with the guide under both. Tabs could only ever show the first tier and kept the four collections one click deep, behind a segmented control that existed on a single page; as a nav group they are addressed directly, and the tier they used to occupy inside the page is gone.

Each collection carries how many records it currently holds, withheld until the first load lands rather than counting to zero and correcting itself. The dashboard, the log and the guide carry no figure at all, because none of them is a collection: what the dashboard counts is on the head of the section it counts. The current one is marked by a filled ground and an accent icon, never a heavier word.

The bar above the content holds only what is true of every page: what Janus has announced, refresh, and settings. Below 60rem the rail becomes a drawer behind one button and the wordmark moves into the bar.

Content is capped at 1360px inside the rail, with 24px desktop and 16px mobile gutters.

**Every page is built from the same head**, and the head reserves its own height. A section line, a title, one or two lines of explanation, and a slot on the right: a collection puts its primary button there, a record puts its status. On a record the section line becomes the way back, so a connection opened from the list lands its title on the pixel the list title was on. The explanation holds two lines of floor whether the copy fills them or not.

**Nothing appears late in a place that pushes what is under it.** The scrollbar gutter is reserved at the root, so moving between a page that overflows and one that does not never slides the layout sideways. Bands that wait on a second request, the findings above the connections and the traffic figures above the log, are held open at their own height while they load.

**Structure changes at 60rem, not just size.** `DataTable` takes one column declaration and renders a real table on wide screens and stacked records on narrow ones, chosen in JS so the DOM is never duplicated. One column is the record heading, one or two are badges beside it, the rest become a definition list. A six-column table behind a horizontal scrollbar is not a mobile experience.

Forms live in a right-hand panel at 480px, and become a bottom sheet below 60rem. The setup flow is the exception: it takes the window, capped at 736px, because it is a task with a beginning and an end rather than an edit beside the console.

## Where you are is in the address bar

Every destination has a URL — `/dashboard`, `/connections/{id}`, `/activity`, `/registry/{record}` — so a page can be linked to, reloaded, and reached with the back button. A connection keeps an address of its own rather than one under the dashboard, because it is a record and not a state of the page that lists it; `/connections` on its own, which was the home before the dashboard was, lands on the dashboard. Position used to live in component state, which meant none of that worked and a refresh always landed on the home page. nginx serves `index.html` on any unmatched path, so a deep link survives a cold load.

Seven destinations do not justify a routing library. The History API and one parser do it, in one file that knows the shape of every URL.

Pages are code-split. The registry and the setup flow are not what most visits are for, and their code is not in the bundle that draws the first screen.

## Data, and when it is refetched

Server state is held in TanStack Query rather than in the shell. The console used to reload all five collections after any change, which made every edit cost five requests and made the whole page flicker. Now a change states what it makes stale and only that is refetched — with the subtlety that the API denormalises names: renaming a provider invalidates credentials and grants with it, rather than leaving a name on screen that no longer exists anywhere.

Two consequences are visible. The connections page and the activity log read the same page of the audit stream, so moving between them costs no request. And credentials refused anywhere return the whole console to the sign-in screen, rather than letting a background refetch fail quietly against a password that stopped working.

## Motion

160ms on state, 220 to 240ms on panels, `cubic-bezier(0.22, 1, 0.36, 1)`. Motion signals state change, disclosure, or completion. There is no page-load choreography. `prefers-reduced-motion` removes transforms and collapses durations.

## Language

English and French, detected from `navigator.languages` and overridable in the settings menu. English is the source dictionary and every other locale is typed against its shape, so a missing key is a compile error. Dates, times, and numbers go through `Intl` with the active locale; backend enums are translated by lookup with a readable fallback for values the console does not know yet.
