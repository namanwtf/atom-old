# Optional Characters

Atom's default experience is the phone agent. The sidebar's "Characters" provides optional conversation personas: only conversations started from a character's detail page use that character; regular new conversations and system assistant entry points continue to use the default Atom. Models, tool toggles, Skills, and MCP reuse the existing configuration.

## Usage

Once you enter the character library you can create, search, or import characters; open a character's details, choose a greeting, and start a conversation. A new character library ships with a built-in character "Xiaoman", which can be edited or deleted; if the library becomes empty after deletion, she can be restored from the empty-library state. The greeting is used directly as the first character message without triggering a model request. The user persona can set a name and description; when unset, "User" is used.

The edit page shows name, character description, and greeting by default; advanced settings hold personality, scenario, example dialogue, system prompt, and worldbook content. After saving a character card, the next request in associated conversations uses the new content; running tasks keep the settings from when they started. Deleting a character does not clear existing conversations (they retain a snapshot of the character at the time), and the original image and story memory under the character's directory are removed together; the operation cannot be undone. Copying a character creates a new character and story-memory space. Atom's UI does not display avatars; the original image of an imported PNG character card is still kept locally, used only for export compatibility.

A character's identity and tone do not replace phone actions: real tasks continue to call the enabled tools and answer based on actual execution results. Default assistant conversations do not load character cards, worldbooks, or story memory.

## Exchange Format

| Format | Import | Export |
| --- | --- | --- |
| V1 JSON | Supported, converted to the modern character structure | Converted to V2 |
| V2 / V3 JSON | Supported | Kept at the corresponding version |
| PNG character card | Reads `chara` / `ccv3` metadata | Writes consistent V2 / V3 metadata simultaneously |

When a PNG contains two character blocks, `ccv3` is read first; if the preferred block is corrupted an error is reported rather than silently falling back to the other definition. Ordinary images cannot be imported directly as character cards. Re-importing creates a duplicate.

Characters are saved as a complete JSON tree. When editing supported fields, unknown fields, worldbook data, and extensions are preserved; round-tripping does not guarantee that JSON indentation and field order stay unchanged. PNG export keeps the original image's non-character data chunks and replaces the character metadata; cards without an original image use a simple default image on export.

Formats follow [Character Card V2](https://github.com/malfoyslastname/character-card-spec-v2/blob/main/spec_v2.md), [Character Card V3](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md), and the [PNG metadata implementation](https://github.com/SillyTavern/SillyTavern/blob/release/src/character-card-parser.js). Saving some data does not mean every extension effect is applied.

## Persona and Worldbook

Character description, personality, scenario, example dialogue, and system prompt go into the character background; author notes and tags are for management only. `{{char}}`, `{{user}}`, `{{original}}`, and common name placeholders are supported; unknown macros keep their original text and are listed in the compatibility notice. `{{original}}` references Atom's default character expression template; cards cannot replace the real tool permissions and execution contract.

Embedded worldbooks support enabling, constant (always-on) entries, primary/secondary keywords, case sensitivity, scan depth, recursion, insertion order, and token budget. Matching scans only user and character text; tool output, thinking, and image observations are not used as trigger words. By default it scans the last two messages and disables recursion; the default entry budget is 25% of the user-configured model window, without estimating the remaining capacity of the whole request. Entries are selected by constant, priority, and order; entry text is not truncated when the budget is exceeded. The token estimate of an entry is used only for the character-card budget and does not participate in automatic compression; explicit zero values and defaults are handled separately.

Character depth notes are positioned against the conversation text; projection does not split apart tool calls and results. The actual provider's merge rules for `system` messages still apply, so system character notes are merged into the top-level system prompt in some protocols; their content is preserved, but the same positional effect as every Tavern preset is not guaranteed.

The current implementation targets common text character cards; it does not execute complex variables, regex substitution plugins, HTML status bars, or scripts, nor does it support group chat, CHARX, plugins, or preset systems. Worldbooks use literal keyword matching; unsupported conditions such as regex keywords, probability, grouping, timing, and special insertion positions cause the corresponding entry to be skipped rather than degrading into unconditional triggering. The relevant data is preserved, and details provide a collapsible compatibility note.

## Real-World and Story Memory

Character conversations can read the default assistant's real-world `MEMORY.md`, but their memory tools do not write to that file. Story is saved to the current character's isolated memory by `character_memory_get` and `character_memory_write`; the model cannot target other characters through this pair of tools. The memory master switch, core budget, and revision conflict rules continue to apply.

Different conversations for the same character share long-term story memory; it can be viewed and edited from the character's detail page. Editing chat or regenerating a reply does not automatically roll back story memory, nor does it undo phone actions that have already been performed.

Character card export contains only the character definition and card image, not the user persona, chat, real-world memory, story memory, or model credentials. Atom's full backup includes these on-device character data and text revisions; import supports older backups.

## Editing and Regeneration

User messages and character text in character conversations support in-place editing, keeping subsequent chat and not automatically rerunning. The next model round uses the revised text; the original request, actual tool calls, results, and archive are each retained, and old tool results cannot be treated as evidence that a modified request has already run.

Regeneration of a character reply only rewrites the target text, using the history before the target, the original reply, and tool results allowed to be saved. That request does not run device tools, Skills, MCP, or managed tools, nor does it accept additional instructions; it can be stopped. On success you can switch between reply candidates; on failure or interruption the original reply is kept. After the rewrite, send a new message from the chat input to continue; the running overlay does not turn this rewrite into a new task. Already-redacted material and images are not re-read to fill in evidence.

Regular assistant conversations keep their existing editing and re-execution behavior. Deleting a conversation from a given turn also does not undo device operations or memory writes that have already occurred.
