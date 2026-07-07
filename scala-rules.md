# Scala 3 LLM Guidelines & Coding Rules

You are acting as an expert Scala engineer. When writing, refactoring, or reviewing Scala code in this codebase, you must follow these rules strictly:

## 1. Syntax & Style
* Syntax and coding styles are controlled automatically by Scalafmt and Scalafix linting rules.

## 2. Functional Programming Standards
* **Pure FP Style**: Always write code in a pure functional programming style.
* **Thought patterns**: Math based. Think on project as objects and relationships (category theory), ADTs. Use abstractions, layers of abstractions (from business to technical), parallel/sequential morphisms, programming on types

## 3. Scala 3 Features
* Feel free to use advanced Scala 3 features: `given`/`using` for implicits, `enum` for ADTs, extension methods, typeclasses, derivations, type lambdas, union/intersection types, opaque types.

## 4. Cats & Cats Effect (Typelevel Ecosystem)
* Use Cats Effect for managing side effects and concurrency.
* **Abstraction First**: Prefer programming to abstract typeclasses (e.g., `Monad`, `Sync`, `Concurrent`, `Temporal`, `ApplicativeError`) instead of concrete types/instances (like `IO`) to ensure generic, composable, and easily testable code.
* Avoid running IO unsafely (never call `unsafeRunSync`). Let the runtime execute the IO at the application entry point (`IOApp`).
* Use cats syntax import (`import cats.syntax.all.*`) for map, flatMap, traverse, sequence, etc.

## 9. Testing Guidelines (MUnit)
* Write tests using **MUnit**. Extend `munit.FunSuite`.
* **Preferred Styles**: Prefer Property-Based (PB) testing, Golden (snapshot) testing, and mutation testing via Stryker4s.
* **Formal Verification**: Search for opportunities to apply Stainless formal verification to functional properties and core logic.
* Leverage MUnit assertions like `assertEquals`, `assertNotEquals`, `intercept`.

## 10. Formal Verification (Stainless)
* Write pure mathematical specifications.
* Annotate verified code with `@pure`, `@ghost`, or `@extern` where appropriate.
* Avoid mutable state or unsupported Scala features in verified code sections.

## 11. Mutation Testing (Stryker)
* Write comprehensive tests that verify behavior under mutation.
* Ensure tests are not brittle or order-dependent.

## 13. Code Quality (Scalafmt, Scalafix, Wartremover)
* Keep code formatted via Scalafmt rules.
* Use Scalafix to organize imports and remove unused imports or syntax warnings automatically.
* **Wartremover**: Pure functional programming safety is checked via Wartremover's Unsafe warts. Ensure your code does not trigger any unsafe warts (such as `Null`, `Var`, `Throw`, `Return`, `IsInstanceOf`, `AsInstanceOf`).

## 18. ScalaSemantic MCP Rules
* For any Scala (`.scala`) source questions, file operations, search, or analysis, use ScalaSemantic MCP tools before shell text tools.
* Preferably compile code before usage, therefore more ScalaSemantic functions could be used with better result.
* **NEVER** use generic text/file-reading, viewing, or searching tools (like `view_file`, `grep_search`, or shell commands like `rg`/`grep`/`cat`/`sed`) on `.scala` files unless the MCP tools are unavailable or failing.
* **ALWAYS** use the custom tools provided by the `scala-semantic` MCP server:
  * **To read/view the contents of a file**: Use the `annotated_source` MCP tool.
  * **For all other queries** (searching, finding usages, hierarchies, etc.): Select the appropriate tool from the registered `scala-semantic` MCP tools.

## 17. Project Maintenance
* **Scala Steward**: Periodically run Scala Steward updates to keep the project's dependencies and compiler plugins up-to-date.

## 19. Command Execution & Token Output Minimization
* **Minimize Output Volume**: To prevent token bloat, always minimize stdout/stderr output when running commands. Avoid dumping massive log streams, command traces, or verbose build success messages into the LLM context.
* **Use Token-Optimized CLI Proxies**: If `rtk` (Rust Token Killer) is installed and verified, prefix commands with `rtk` (e.g. `rtk git status`, `rtk sbt test`) to leverage transparent token-filtering proxying.
* **Filter Log Streams & Errors**: When running any scala related commands, apps, scripts, tools, when whole result not required, redirect or pipe outputs to isolate errors or limit lines:
  * Pipe compile/test logs to `grep` with context flags to capture only errors (e.g., `| grep -C 3 -i error` or `| grep -i fail`).
  * Use `head -n N` or `tail -n N` to capture only a small, representative slice of command outputs when scanning general outputs.
  * Suppress standard success logs or stdout using redirect syntax (`> /dev/null`) if you only need the command exit status or error streams.

