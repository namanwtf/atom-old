# Atom Settings and Management Components

Atom supports a global UI style switch, offering **Miuix** and **COUI** choices. New installs default to Miuix. Users can switch via "Settings → Appearance & theme → UI style"; the choice is saved and restored on later upgrades. Both UIs share the same business state, Store, Repository, and navigation stack; switching styles moves the same composition tree via `movableContentOf` in `AgentAppTheme`, without losing navigation history, page drafts, or running tasks.

## Root Theme and Scaling Invariants

- `AgentAppTheme` provides both `COUITheme` (COUI mode) and `MiuixTheme`, and exposes the current `AppearanceUiStyle` (`COUI` / `MIUIX`) via `LocalUiStyle`.
- COUI mode uses the COUI `Scaffold` at the Activity root to host overlays. Pages record backgrounds on demand, with the top bar and overlays sampled outside the recording area. Standalone system floating windows use solid colors and rounded corners suitable for a software canvas.
- Miuix mode uses the HyperOS 4 (OS4) glass architecture: `MainActivity` and `AgentBrowserActivity` mount `Scaffold`, `LocalGlassSceneBackdrop`, and the full-window `EtaTopLayerHost` at the root via `AgentAppTheme(provideGlassHost = true)` (COUI and non-Activity floating windows do not mount this host), letting `GlassDialog`, `GlassBottomSheet`, `GlassPopup`, and `GlassTransformPopup` sample the scene without self-sampling; secondary pages provide `LocalGlassPageBackdrop` and `GlassTopAppBar` via `MiuixScaffoldPage` / `MiuixScaffold`.
- Pages and overlays in both styles use the display density and font scaling provided by Android.
- The custom top bars of the chat and conversation sidebar only reserve space for foreground content, and the background can extend downward to form a fade. Each style uses its own component library's blur and fallback styles. The fixed COUI top bar uses full frosted-glass effect; the collapsible COUI top bar appears as you scroll.

## Component Selection and Dual-UI Organization

The page layer declares `rememberSaveable`, draft state, Store, and side effects before the `when (LocalUiStyle.current)` branch, then organizes the COUI and Miuix UI compositions separately:

| Scenario | COUI composition (`ui/components/coui/` and `io.github.suqi8.coui.kmp.*`) | Miuix composition (`ui/components/` and `top.yukonga.miuix.kmp.*`) |
| --- | --- | --- |
| Secondary page (Column + overscroll) | `CouiScaffoldPage` (`Scaffold` + `TopAppBar` / `SmallTopAppBar` + `overScrollVertical`) | `MiuixScaffoldPage` (`GlassTopAppBar`) |
| Secondary page (long dynamic list) | `CouiLazyScaffoldPage` | `MiuixScaffoldPage` (`GlassTopAppBar`) |
| Custom container secondary page (tabs, full-page editor) | `CouiScaffold` | `MiuixScaffold` (`GlassTopAppBar`) |
| Top-bar back and action buttons | `CouiBackButton`, `CouiTopAppBarIconButton`, `CouiTopAppBarTextButton` | `MiuixBackButton`, `MiuixTopBarActionButton`, `TextButton` (`GlassIconButton` + `MiuixIcons.Glass.*`) |
| Grouped cards and titles | `CouiPreferenceGroup` (`PreferenceCategory` + `Card` + `CardListGroup`) | `SmallTitle` + `Card` |
| Lazy continuous group items | `EtaPreferenceGroupItem` → `CouiGroupItem` | `EtaPreferenceGroupItem` |
| Navigation row / info row | `CouiPreference` (`ArrowPreference` / `BasicComponent`) | `ArrowPreference` / `BasicComponent` |
| Switch row | `CouiSwitchPreference` (`SwitchPreference` / `SwitchLoadingPreference`) | `SwitchPreference` |
| Dropdown / popup selection row | `CouiDropdownPreference` / `CouiPopupPreference` (`OverlayDropdownPreference` / `OverlayListPopup`) | `GlassDropdownPreference` / `EtaGlassSpinnerPreference` (based on `GlassDropdownPopup` + `EtaTopLayer`) |
| Checkbox / radio row | `CouiCheckboxPreference` | `CheckboxPreference` / `Checkbox` |
| In-group divider | `CardListGroup` draws adjacent-row dividers | Miuix list spacing |
| Search bar | page top bar uses `TopAppBarSearchBar`, panel uses `SearchBar` | `SearchBar` + `InputField` |
| Tab / filter chips | `CouiChipTabs` (COUI `ChipTabRow`) | `GlassSegmentedTabRow` / `EtaChoiceChip` |
| Inline text field | `CouiTextField` | `TextField` |
| Page capsule button / floating toolbar | `CouiButton` (`CouiButtonRole`), `FloatingToolbar` | `TextButton`, `GlassFloatingToolbar` |
| Confirm / notice / bottom sheet | `CouiConfirmDialog`, `CouiNoticeDialog` (`OverlayDialog`), `CouiBottomSheet` (`OverlayBottomSheet`) | persistent-composition `GlassDialog` (with `MiuixDialogActions` / `GlassDialogButton`) and `GlassBottomSheet` |
| Single-line input dialog | `CouiInputDialog` (`OverlayDialog` + `TextFieldState`) | `GlassDialog` + `TextField` |
| Context / overflow menu | `OverlayListPopup` + `DropdownImpl` | `EtaTopLayer` + `GlassPopup` / `GlassTransformPopup` + `GlassPopupItem` |

## Grouping, Dividers, and State Boundaries

- COUI groups register each row via `CardListGroup`'s `item`, and the component handles card positioning and dividers for conditional rows.
- Long lists use independent Lazy items with stable keys and `EtaPreferenceGroupItem` (COUI automatically switches to `CouiGroupItem`), so each piece of dynamic data participates in lazy loading independently.
- Switch, checkbox, radio, input draft, and popup states are all held by the page or Store above `when (LocalUiStyle.current)`, ensuring state and drafts are fully preserved when switching between `COUI` and `Miuix` at runtime.

Model selection, remote model import, and subagent details use native bottom panels in both styles. Scrollable content holds its state within each panel; exiting can be done by pulling down, tapping the scrim, or using system back. The submit button for remote model import is fixed below the list.
