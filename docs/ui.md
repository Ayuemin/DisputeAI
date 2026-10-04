# UI notes

DisputeAI uses a native Kotlin + Jetpack Compose interface.

## Chat

The main screen intentionally keeps the header minimal. Model replies stream into the chat and support Markdown, tables, selectable text and per-model colors.

Pending attachments are collapsed into a single `Вложения N` row. Tapping it opens a vertical list with per-file removal.

## Drawer

The drawer contains the app title, search, New chat, searchable/pinnable chat history and Settings. It can be opened with the menu button or a rightward swipe that may start inside the left 80% of the main chat screen.

## Settings

All settings sections start collapsed. Sections behave as an accordion: opening another section on the same level closes the previous one.

- `Участники дискуссии` contains every participant model, Add model and the result model; model cards are separated visually and also open one at a time.
- Each model header toggles expansion across the whole header area, not only the arrow.
- `Параметры дискуссии` contains expandable groups for cycles/order, cycle prompts and result context.
- Result context defaults to the independent first cycle plus the last 3 cycles and user messages. Enabling `Использовать весь контекст дискуссии` hides the last-cycle counter.
- `О приложении` contains version/repository information, API-key security notes, background-work controls and settings reset.

The dark color scheme is intentionally high-contrast so secondary labels remain readable on phone displays.
