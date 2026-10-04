# UI notes

DisputeAI uses a native Kotlin + Jetpack Compose interface.

## Chat

The main screen intentionally keeps the header minimal. Model replies stream into the chat and support Markdown, tables, selectable text and per-model colors.

Pending attachments are collapsed into a single `Вложения N` row. Tapping it opens a vertical list with per-file removal.

## Drawer

The drawer contains the app title, search, New chat, searchable/pinnable chat history and Settings. It can be opened with the menu button or a rightward swipe that may start inside the left 80% of the main chat screen.

## Settings

Settings are organized as expandable cards and their expansion state is saveable across normal recompositions.

- `Участники дискуссии` contains every participant model, Add model and the result model.
- Each model header toggles expansion across the whole header area, not only the arrow.
- `Параметры дискуссии` contains expandable groups for cycles/order, cycle prompts and result context.
- `О приложении` contains version/repository information, API-key security notes, background-work controls and settings reset.

The dark color scheme is intentionally high-contrast so secondary labels remain readable on phone displays.
