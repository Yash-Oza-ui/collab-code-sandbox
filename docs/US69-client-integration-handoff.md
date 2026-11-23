# Client Integration Specification: US-69 Highlight Changed Lines in a Slowdown Run

**Target Audience:** Het Kapadiya & Niranjan Panchal (Client Owners)  
**Author:** AI/ML Service Scope (Prince Gadara)  
**Issue:** [#220 — US-69: Highlight what changed in a slow run](https://github.com/Yash-Oza-ui/collab-code-sandbox/issues/220)  
**Traceability:** FR37.3 · US-69 · EPIC 4 · Sprint 5  
**Scope Notice:** AI/ML scope is strictly prohibited from modifying `client/`. This document is the authoritative integration specification for client-side implementation.

---

## 1. Response Structure

The client receives slowdown analysis results through the existing WebSocket `analysis-result` message envelope (`task: "slowdown"`).

```json
{
  "type": "analysis-result",
  "request_id": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
  "analysis_id": "2d3e4f5a-6b7c-8d9e-0f1a-2b3c4d5e6f7a",
  "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
  "task": "slowdown",
  "status": "ok",
  "ai_step": {
    "status": "ok",
    "error_code": null
  },
  "result": {
    "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "is_slowdown": true,
    "slowdown_factor": 14.2,
    "baseline": {
      "median_ms": 100.0,
      "mad_ms": 6.2,
      "sample_count": 7
    },
    "changed_lines": [
      {
        "line": 2,
        "module_id": null
      }
    ],
    "removed_lines_count": 0,
    "comparison_available": true,
    "comparison_note": null,
    "explanation": "Runtime regression caused by newly introduced blocking sleep call on line 2."
  },
  "message": "Execution runtime was 14.2x slower than normal baseline."
}
```

### Key Fields to Inspect in `result`
- `is_slowdown` (boolean): Highlights should ONLY be rendered when `is_slowdown === true`.
- `changed_lines` (`Array<{ line: number, module_id: string | null }>`): 1-based line numbers in the **current source code**.
- `removed_lines_count` (number): Count of deleted lines. Cannot be highlighted in the current editor because they no longer exist.
- `comparison_available` (boolean): `true` when a previous normal run was found and compared; `false` when no previous normal run exists or run was unflagged.
- `comparison_note` (string | null): Short explanation, e.g., `"No earlier normal run to compare."` or `"1 line removed."`.
- `message` (string): Summary regression message.

---

## 2. Identifying the Relevant Result & Preserving Audience / Privacy

- **Asynchronous Delivery (NFR9, CR08):** The slowdown analysis result arrives via WebSocket after execution output is already displayed.
- **Server-Authoritative Visibility:** The client **MUST NOT** decide who should see the result. The WebSocket relay (`sync-service`) delivers the message strictly based on the run's visibility:
  - In **Pair mode**: broadcast to the room audience.
  - In **Open mode**: private run results are delivered exclusively to the runner's session.
- **Matching Runs:** Correlate `request_id` or `input_hash` to ensure the highlight corresponds to the active or most recent run. Stale or superseded results must not overwrite newer states.

---

## 3. Monaco Editor Decoration Architecture (Strict Invariants)

### ⚠️ CRITICAL COLLABORATION INVARIANT: Zero Yjs Mutation
- **NEVER write decorations or highlight metadata into the shared Yjs document (`Y.Doc` or `y-monaco`).**
- **NEVER broadcast decoration changes as collaborative document transactions.**
- Decorations are **ephemeral, local presentation state only**. Writing decorations to Yjs would cause remote editor corruption, phantom undo/redo steps, and collaborative sync conflicts.

### Suggested Monaco Decoration Pattern
Use Monaco's `editor.deltaDecorations()` (or `editor.createDecorationsCollection()`) with an editor-local decoration ID ref.

```typescript
// Example: React component local decoration state
import { useEffect, useRef } from "react";
import * as monaco from "monaco-editor";

interface ChangedLineItem {
  line: number;
  module_id?: string | null;
}

export function useSlowdownDecorations(editor: monaco.editor.IStandaloneCodeEditor | null) {
  const decorationIdsRef = useRef<string[]>([]);

  const clearDecorations = () => {
    if (editor && decorationIdsRef.current.length > 0) {
      decorationIdsRef.current = editor.deltaDecorations(decorationIdsRef.current, []);
    }
  };

  const applyHighlights = (changedLines: ChangedLineItem[]) => {
    if (!editor) return;

    // Convert 1-based line numbers into Monaco IModelDeltaDecoration objects
    const newDecorations: monaco.editor.IModelDeltaDecoration[] = changedLines.map((item) => ({
      range: new monaco.Range(item.line, 1, item.line, 1),
      options: {
        isWholeLine: true,
        className: "slowdown-changed-line-highlight",
        glyphMarginClassName: "slowdown-glyph-margin-icon",
        hoverMessage: { value: "**Slowdown Investigation:** This line changed since the last normal run." },
        // Stickiness ensures decoration moves naturally with user edits
        stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
      },
    }));

    decorationIdsRef.current = editor.deltaDecorations(decorationIdsRef.current, newDecorations);
  };

  return { applyHighlights, clearDecorations };
}
```

### CSS Styling Suggestion
```css
/* Subtle amber/orange background tint for changed lines in slowdown run */
.slowdown-changed-line-highlight {
  background-color: rgba(255, 179, 0, 0.15) !important;
  border-left: 3px solid #ffb300 !important;
}

.slowdown-glyph-margin-icon {
  background: url("data:image/svg+xml,...") center center no-repeat;
}
```

---

## 4. Mapping to Editors & Open Mode Module Support

- **Pair Mode:** Only one shared active editor exists. All items in `changed_lines` map directly to `editor.getModel()`.
- **Open Mode:** Each module possesses its own editor instance or model:
  - If `item.module_id` is present, look up the editor/tab corresponding to `item.module_id`.
  - Apply highlights only to that specific module's Monaco model.
  - If a module is not currently open, retain the changed line numbers in local state so decorations apply when the user switches to that module tab.

---

## 5. UI Lifecycle & Dismissal Controls

1. **Dismiss Button:**
   - Display a floating dismissible banner or notification above the editor:
     `"Slowdown detected: 2 lines changed since last normal run. [Dismiss]"`
   - Clicking **Dismiss** clears the decoration IDs via `editor.deltaDecorations(ids, [])`.
2. **Removed-Line Banner / Note:**
   - If `removed_lines_count > 0`, display a pill/note in the slowdown banner:
     `"Note: 1 line was deleted since the last normal run."`
3. **No-Earlier-Run State:**
   - If `is_slowdown === true` and `comparison_available === false`:
     - Do not highlight any lines.
     - Display the note: `"Performance slowdown detected (8x slower), but no earlier normal run exists for this input to compare."`
4. **Superseded / New Run:**
   - When a new execution starts or a new analysis result arrives, automatically clear stale decorations.
   - When the editor is unmounted or switched, dispose of decoration collections.
