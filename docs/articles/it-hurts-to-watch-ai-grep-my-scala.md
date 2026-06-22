# It Hurts to Watch an AI Grep My Scala

Why I built a small tool that lets AI assistants, like Claude, understand Scala structure and relationships instead of reading code as plain text.

You know the feeling. You ask the AI in your editor, “Where is doThisWell used?” — and you watch it run grep.

Sometimes it gets a lot of matches. Most are a different doThisWell. A few are in comments. One is inside a string. Then it opens half a dozen files to find the real ones, spends a lot of tokens, and still misses the one call that was renamed on import.

And honestly, it annoys me a little.

Here is the thing: we, the Scala community, already solved this. The Scala compiler knows which symbol each reference points to. It resolves names, tracks types, and writes structured semantic information into SemanticDB.

That data just sits there while the AI next to it keeps guessing with grep.

So I built ScalaSemantic: a small tool for AI coding agents that lets the AI ask the compiler instead of reading the text.

## A short glossary, for people who skipped the AI hype

Three words:

- SemanticDB — the compiler’s semantic database about your code: symbols, types, references, and resolved names. It is written at build time.
- Presentation compiler — the live compiler, the one Metals uses for hover, completions, and go-to-definition. It can also understand code you just typed but have not built yet.
- MCP — a standard way to give an AI assistant extra tools. You write a tool, and the AI can call it.

ScalaSemantic connects those compiler sources to the AI as MCP tools. The AI asks, “Who calls Service.run?” and gets the actual callers as the compiler sees them — not just places where the same letters happen to appear.

## Why text search is the wrong tool for code

grep matches characters. The compiler understands symbols.

These are not the same job, and the difference is exactly the part that makes Scala Scala:

- Text search can miss references that were renamed on import, re-exported, or only become visible through inferred types and implicits. No matching text, no result.
- Text search can over-match every name that looks the same, plus comments and strings. Three different apply methods? grep returns all of them.

For a Scala engineer, this does not make much sense. For an AI, it is even worse: every noisy result fills its limited context and is sent again with each follow-up question. The noise accumulates.

I measured it on my own repo: the semantic answer was about 8× smaller than the grep answer, with zero wrong matches in that test. Smaller, exact, and no need to open six files afterward.

Yes, a normal person would just live with it. I built a whole application instead. Everyone copes differently.

## What it is not

It is not magic.

Most answers come from SemanticDB, so they reflect your last build. If the build is stale, the data can be stale too.

The presentation compiler can also inspect code you just typed and have not built yet, but not every tool uses it yet. And nothing here tries to understand comments or arbitrary plain text. For those, grep is still the right tool — and the server can tell the AI when to use it.

## The point

Here is the part I keep thinking about.

We spent years building compilers that understand our code precisely. Then we handed the code to AI assistants and let them read it with the simplest tool available.

That is a little strange.

The structured knowledge already exists. Someone just had to connect it.

So I connected it.

Now the AI can ask the compiler. It is faster, cheaper, and stops embarrassing itself in front of my code.

A small win. But an honest one.

---

ScalaSemantic is open source. If you want the AI working on your Scala code to stop guessing, give it a try — and tell me where it gets things wrong.