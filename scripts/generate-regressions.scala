#!/usr/bin/env -S scala-cli shebang
//> using scala "3.8.4"
//> using dep "com.lihaoyi::os-lib:0.11.3"
//> using dep "com.lihaoyi::upickle:4.2.1"

import java.io.File
import scala.collection.mutable

object GenerateRegressions:
  val RegressionsDir = os.pwd / "regressions"
  val FilesMap = Map(
    "unit" -> (RegressionsDir / "unit-tests.json"),
    "e2e" -> (RegressionsDir / "e2e-tests.json"),
    "stryker" -> (RegressionsDir / "stryker-mutants.json")
  )

  case class Args(
    mode: String = "",
    base: String = "origin/master",
    categories: List[String] = List("unit", "e2e", "stryker")
  )

  def parseArgs(args: List[String]): Args =
    var mode = ""
    var base = "origin/master"
    var categories = List.empty[String]

    var i = 0
    while i < args.length do
      args(i) match
        case "--write" =>
          mode = "write"
          i += 1
        case "--check" =>
          mode = "check"
          i += 1
        case "--check-regression" =>
          mode = "check-regression"
          i += 1
        case "--base" if i + 1 < args.length =>
          base = args(i + 1)
          i += 2
        case "--categories" =>
          i += 1
          val cats = mutable.ListBuffer.empty[String]
          while i < args.length && !args(i).startsWith("-") do
            cats += args(i)
            i += 1
          categories = cats.toList
        case other =>
          System.err.println(s"Error: Unknown argument: $other")
          sys.exit(1)
    
    if mode.isEmpty then
      System.err.println("Error: One of --write, --check, or --check-regression is required.")
      sys.exit(1)
    
    if categories.isEmpty then
      categories = List("unit", "e2e", "stryker")
      
    Args(mode, base, categories)

  def collectUnitTests(): List[String] =
    val exclusions = Set(
      "com.github.mercurievv.scalasemantic.mcp.TokenMetricsSuite.token metrics artifacts match regenerated MCP-vs-baseline comparison"
    )
    val unitTests = mutable.Set.empty[String]

    if os.exists(os.pwd / "out") then
      val outDirs = os.walk(os.pwd / "out")
      for path <- outDirs if path.last == "out.json" && (path.toString.contains("testForked.dest") || path.toString.contains("testOnly.dest")) do
        try
          val json = ujson.read(os.read(path))
          if json.arr.length > 1 && json(1).isInstanceOf[ujson.Arr] then
            for test <- json(1).arr do
              if test.obj.contains("fullyQualifiedName") && test.obj.contains("status") then
                val name = test("fullyQualifiedName").str
                val status = test("status").str
                if status == "Success" then
                  if !exclusions.contains(name) && !name.contains("CompatSuite.[corpus") then
                    unitTests += name
        catch
          case e: Exception =>
            System.err.println(s"Warning: failed to parse unit test output $path: $e")
    unitTests.toList.sorted

  def collectE2eTests(): List[String] =
    val e2ePath = os.pwd / "out" / "e2e-report.json"
    if os.exists(e2ePath) then
      try
        val json = ujson.read(os.read(e2ePath))
        json.arr.map(_.str).toList.sorted
      catch
        case e: Exception =>
          System.err.println(s"Warning: failed to parse E2E report $e2ePath: $e")
          Nil
    else Nil

  def collectStrykerMutants(): List[String] =
    val strykerMutants = mutable.Set.empty[String]
    val paths = mutable.ListBuffer.empty[os.Path]

    // The repo-root mutation-report.json is a legacy fallback destination that scripts/run-stryker.sh
    // only writes when a caller doesn't set MUTATION_REPORT_DEST (see scripts/mutation-summary.sh).
    // CI always sets MUTATION_REPORT_DEST to reports/mutation/<module>/report.json and only commits
    // that path back (.github/workflows/mutation.yml "Commit mutation report" step), so the root copy
    // is never refreshed by automation. Reading it here would let a mutant that genuinely disappears
    // from the live report keep showing up as "present" via the stale root copy, masking a real
    // coverage regression. reports/mutation/<module>/report.json is the sole source of truth.
    if os.exists(os.pwd / "reports" / "mutation") then
      for p <- os.walk(os.pwd / "reports" / "mutation") if p.last == "report.json" do
        paths += p

    for path <- paths do
      val moduleName = path.toIO.getParentFile.getName
      try
        val report = ujson.read(os.read(path))
        if report.obj.contains("files") then
          for (filePath, fileData) <- report("files").obj do
            if fileData.obj.contains("mutants") then
              for mutant <- fileData("mutants").arr do
                val mutator = mutant.obj.get("mutatorName").map(_.str).getOrElse("Unknown")
                val status = mutant.obj.get("status").map(_.str).getOrElse("Unknown")
                val repl = mutant.obj.get("replacement").map(_.str).getOrElse("")

                val replClean = repl.replace("\n", "\\n").take(50)
                val statusMapped = if status == "Survived" || status == "NoCoverage" then "Survived" else status
                val desc = s"Stryker:$moduleName:$filePath:$mutator:$replClean -> $statusMapped"
                strykerMutants += desc
      catch
        case e: Exception =>
          System.err.println(s"Warning: failed to parse Stryker report $path: $e")
    strykerMutants.toList.sorted

  def getAvailableMutationModules(): List[String] =
    val modules = mutable.Set.empty[String]
    if os.exists(os.pwd / "reports" / "mutation") then
      for p <- os.list(os.pwd / "reports" / "mutation") if os.isDir(p) do
        if os.exists(p / "report.json") then
          modules += p.last
    modules.toList

  def generateAll(categories: List[String]): Map[String, List[String]] =
    val results = mutable.Map.empty[String, List[String]]
    if categories.contains("unit") then
      results("unit") = collectUnitTests()
    if categories.contains("e2e") then
      results("e2e") = collectE2eTests()
    if categories.contains("stryker") then
      results("stryker") = collectStrykerMutants()
    results.toMap

  def getBaseFileContent(baseRef: String, path: String): Option[String] =
    try
      val res = os.proc("git", "show", s"$baseRef:$path").call(stderr = os.Pipe, check = false)
      if res.exitCode == 0 then Some(res.out.text()) else None
    catch
      case _: Exception => None

  def isApproved(): Boolean =
    val envApp = sys.env.get("REGRESSION_APPROVED").map(_.toLowerCase)
    val byEnv = envApp.contains("true") || envApp.contains("1") || envApp.contains("yes")

    val byCommit =
      try
        // On a pull_request-triggered checkout, actions/checkout puts HEAD at GitHub's synthetic
        // merge commit (refs/pull/N/merge), whose message is autogenerated boilerplate — not the
        // developer's commit. That merge commit's second parent is the actual PR head commit
        // (first parent is the base branch tip), so read the message from there when present.
        val parents = os.proc("git", "log", "-1", "--pretty=%P").call().out.text().trim.split(" ")
        val commitRef = if parents.length == 2 then parents(1) else "HEAD"
        val res = os.proc("git", "log", "-1", "--pretty=%B", commitRef).call()
        val msg = res.out.text().toLowerCase
        msg.contains("[approve-regression]") || msg.contains("[approve regression]")
      catch
        case _: Exception => false

    val byPrLabel =
      sys.env.get("GITHUB_EVENT_PATH").map(os.Path(_)).filter(os.exists).exists { eventPath =>
        try
          val eventData = ujson.read(os.read(eventPath))
          val labels = eventData("pull_request")("labels").arr
          labels.exists(label => label("name").str == "regression-approved")
        catch
          case e: Exception =>
            System.err.println(s"Warning: failed to read GITHUB_EVENT_PATH: $e")
            false
      }

    byEnv || byCommit || byPrLabel


  def checkConsistency(generated: Map[String, List[String]], categories: List[String]): Boolean =
    var mismatch = false
    for category <- categories do
      val path = FilesMap(category)
      if !os.exists(path) then
        System.err.println(s"Error: Regression file $path does not exist. Please generate it first.")
        mismatch = true
      else
        try
          val committed = ujson.read(os.read(path)).arr.map(_.str).toList
          var actualGen = generated(category)
          var actualCom = committed

          if category == "stryker" then
            val activeModules = getAvailableMutationModules().toSet
            if activeModules.isEmpty then
              println("Info: No Stryker reports found. Skipping stryker consistency check.")
            else
              actualGen = actualGen.filter(item => activeModules.contains(item.split(":")(1)))
              actualCom = committed.filter(item => activeModules.contains(item.split(":")(1)))

          if actualCom != actualGen then
            println(s"Mismatch in $path!")
            val actualGenSet = actualGen.toSet
            val actualComSet = actualCom.toSet
            val removed = actualCom.filterNot(actualGenSet)
            val added = actualGen.filterNot(actualComSet)
            if removed.nonEmpty then
              println("Removed items:")
              removed.foreach(item => println(s"-  $item"))
            if added.nonEmpty then
              println("Added items:")
              added.foreach(item => println(s"+  $item"))
            mismatch = true
        catch
          case e: Exception =>
            System.err.println(s"Error: Failed to load $path: $e")
            mismatch = true

    if mismatch then
      println("\nTest results do not match committed regression files!")
      println("Please regenerate and commit them using:")
      println("  scala-cli scripts/generate-regressions.scala -- --write")
      false
    else
      println("Committed regression files are up to date.")
      true

  def checkRegressions(baseRef: String, categories: List[String]): Boolean =
    println(s"Checking for regressions against base ref: $baseRef ...")
    var regressionsFound = false
    val missingItemsByCat = mutable.Map.empty[String, List[String]]

    for category <- categories do
      val path = FilesMap(category)
      val relPath = path.relativeTo(os.pwd).toString
      getBaseFileContent(baseRef, relPath) match
        case None =>
          println(s"Info: File $path does not exist on base ref $baseRef. Skipping category '$category'.")
        case Some(baseContent) =>
          try
            val baseData = ujson.read(baseContent).arr.map(_.str).toList
            val currData = ujson.read(os.read(path)).arr.map(_.str).toList

            var actualBase = baseData
            var actualCurr = currData

            if category == "stryker" then
              val activeModules = getAvailableMutationModules().toSet
              if activeModules.isEmpty then
                println("Info: No Stryker reports found. Skipping stryker regression check.")
              else
                actualBase = baseData.filter(item => activeModules.contains(item.split(":")(1)))
                actualCurr = currData.filter(item => activeModules.contains(item.split(":")(1)))

            val currSet = actualCurr.toSet
            val missing = actualBase.filterNot(currSet)
            if missing.nonEmpty then
              missingItemsByCat(category) = missing
              regressionsFound = true
          catch
            case e: Exception =>
              System.err.println(s"Error: Failed to parse content for $category: $e")

    if regressionsFound then
      println("\n⚠️ REGRESSION DETECTED!")
      println("The following tests/mutants disappeared compared to the base branch:")
      for (category, items) <- missingItemsByCat do
        println(s"\n[$category] - ${items.size} items removed:")
        for item <- items do
          println(s"  - $item")

      if isApproved() then
        println("\n✅ Regression was explicitly approved (via PR label, commit message, or environment variable).")
        true
      else
        println("\n❌ Check failed. Action required:")
        println("  1. Fix the regression (restore the missing tests or behavior).")
        println("  2. OR obtain explicit approval by doing one of the following:")
        println("     - Add the 'regression-approved' label to the Pull Request.")
        println("     - Include '[approve-regression]' in your latest commit message.")
        println("     - Set the env var REGRESSION_APPROVED=true in your environment.")
        false
    else
      println("No regressions detected (no tests or mutants were removed).")
      true

  @main def main(rawArgs: String*): Unit =
    val args = parseArgs(rawArgs.toList)

    args.mode match
      case "write" =>
        val generated = generateAll(args.categories)
        for category <- args.categories do
          val path = FilesMap(category)
          os.makeDir.all(RegressionsDir)
          val jsonStr = ujson.write(ujson.Arr(generated(category).map(ujson.Str(_))*), indent = 2)
          os.write.over(path, jsonStr + "\n")
          println(s"Wrote $path (${generated(category).size} items)")
        sys.exit(0)

      case "check" =>
        val generated = generateAll(args.categories)
        if !checkConsistency(generated, args.categories) then
          sys.exit(1)
        sys.exit(0)

      case "check-regression" =>
        if !checkRegressions(args.base, args.categories) then
          sys.exit(1)
        sys.exit(0)
