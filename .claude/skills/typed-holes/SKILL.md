---
name: typed-holes
description: Use when writing a new def/val body or any function longer than a one-liner in a project served by ScalaSemantic — write the signature with ??? first, compile, then fill each hole with hole_context
---

# Typed holes: skeleton first, fill one hole at a time

The compiler knows what type a position needs and what in scope satisfies it. Ask it at each hole
instead of guessing an API from memory — hallucinated members and wrong types come from definitions
that are neither in the file nor in training data; `hole_context` puts exactly those in front of you.

## Procedure

1. **Skeleton first.** Write the signature(s) with `???` bodies. Steps inside a body become local
   `def step: T = ???` with their types declared. Compile (`<module>.compile`): signature errors
   surface before any body exists.
2. **One hole at a time.** Call `hole_context(uri, line, character, source)` on the first `???`
   (pass the buffer's current text as `source`). Read `expected`:
   - `confidence: exact` → choose from `bindings` (already the right type), `producers` (build it:
     `match: exact` needs only the arguments shown; `match: shape` also needs the type argument to
     line up), `givens` (instances you may `summon`/pass). Read `types` before using a member you
     have not seen in this file.
   - `confidence: unconstrained` → nothing prescribes the type. Declare it (the val's type, the
     def's result type) and ask again.
   - a body longer than about three expressions → split it into further `???` sub-holes with
     declared types rather than writing it in one go.
3. **Fill, compile, repeat.** After each fill run `<module>.compile` before the next hole. A red
   compile is fixed at that hole — never by rewriting other holes.

## Red flags

| Thought | Reality |
|---|---|
| "I know this API" | A member not in this file is exactly where hallucinations live. Ask `hole_context`, read `types`. |
| "I'll fill all the holes, then compile" | One hole, one compile. Errors then have one cause. |
| "A cast/`null` will satisfy the expected type" | Never `asInstanceOf`/`null` to satisfy a type. Build the value from `producers` or add a binding. |
| "The candidate lists are empty, so anything goes" | With `confidence: exact` and empty lists the value has to be constructed: ask on a smaller sub-hole or add a parameter/import. |
| "`unconstrained` means I decide the type silently" | Declare it in the source first; the next answer is then `exact`. |
