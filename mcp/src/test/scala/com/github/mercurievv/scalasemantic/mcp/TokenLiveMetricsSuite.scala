package com.github.mercurievv.scalasemantic.mcp

import java.nio.file.Files
import java.nio.file.Path

class TokenLiveMetricsSuite extends munit.FunSuite:

  private val root = Path.of(".").toAbsolutePath.nn.normalize().nn
  private val defaultRunsPath =
    "docs/research/token-metrics-live-runs.sample.json"
  private val defaultOutputPath =
    "docs/research/token-metrics-live.sample.json"

  private val runsPath =
    root.resolve(sys.env.getOrElse("TOKEN_LIVE_RUNS", defaultRunsPath)).nn
  private val outputPath =
    root.resolve(sys.env.getOrElse("TOKEN_LIVE_OUTPUT", defaultOutputPath)).nn

  private case class RunTokens(
      arm: String,
      run: Int,
      inputTokens: Int,
      outputTokens: Int,
      cacheTokens: Int
  ):
    val totalTokens: Int = inputTokens + outputTokens

  private case class Measurement(
      id: String,
      query: String,
      tool: String,
      baseline: String,
      engine: String,
      model: String,
      runs: List[RunTokens]
  )

  private case class Stats(
      runs: List[RunTokens],
      meanTokens: Double,
      medianTokens: Double,
      variance: Double,
      meanInputTokens: Double,
      meanOutputTokens: Double,
      meanCacheTokens: Double
  )

  private def rounded(value: Double): Double =
    BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble

  private def stats(runs: List[RunTokens]): Stats =
    assert(runs.size >= 3, "each arm must have at least 3 runs")
    val totals = runs.map(_.totalTokens).sorted
    val mean = totals.sum.toDouble / totals.size
    val median =
      if totals.size % 2 == 1 then totals(totals.size / 2).toDouble
      else
        val upper = totals.size / 2
        (totals(upper - 1) + totals(upper)).toDouble / 2.0
    val variance =
      totals.map(total => math.pow(total - mean, 2.0)).sum / totals.size
    Stats(
      runs = runs.sortBy(_.run),
      meanTokens = rounded(mean),
      medianTokens = rounded(median),
      variance = rounded(variance),
      meanInputTokens = rounded(runs.map(_.inputTokens).sum.toDouble / runs.size),
      meanOutputTokens = rounded(runs.map(_.outputTokens).sum.toDouble / runs.size),
      meanCacheTokens = rounded(runs.map(_.cacheTokens).sum.toDouble / runs.size)
    )

  private def parseRun(value: ujson.Value): RunTokens =
    val obj = value.obj
    RunTokens(
      arm = obj("arm").str,
      run = obj("run").num.toInt,
      inputTokens = obj("inputTokens").num.toInt,
      outputTokens = obj("outputTokens").num.toInt,
      cacheTokens = obj.get("cacheTokens").map(_.num.toInt).getOrElse(0)
    )

  private def parseMeasurement(value: ujson.Value): Measurement =
    val obj = value.obj
    Measurement(
      id = obj("id").str,
      query = obj("query").str,
      tool = obj("tool").str,
      baseline = obj("baseline").str,
      engine = obj("engine").str,
      model = obj("model").str,
      runs = obj("runs").arr.map(parseRun).toList
    )

  private def parseMeasurements(raw: ujson.Value): List[Measurement] =
    val schemaVersion = raw("schemaVersion").num.toInt
    assertEquals(schemaVersion, 1, "unsupported live metrics schema version")
    raw("measurements").arr.map(parseMeasurement).toList

  private def armStats(measurement: Measurement, arm: String): Stats =
    val armRuns = measurement.runs.filter(_.arm == arm)
    assertEquals(
      armRuns.map(_.run).sorted,
      armRuns.map(_.run).distinct.sorted,
      s"${measurement.id}/${measurement.engine}/$arm has duplicate run numbers"
    )
    stats(armRuns)

  private def savingsPercent(baselineMean: Double, toolMean: Double): Double =
    if baselineMean == 0.0 then 0.0
    else rounded((baselineMean - toolMean) * 100.0 / baselineMean)

  private def runJson(run: RunTokens): ujson.Value =
    ujson.Obj(
      "run" -> run.run,
      "inputTokens" -> run.inputTokens,
      "outputTokens" -> run.outputTokens,
      "cacheTokens" -> run.cacheTokens,
      "totalTokens" -> run.totalTokens
    )

  private def statsJson(stats: Stats): ujson.Value =
    ujson.Obj(
      "runs" -> ujson.Arr.from(stats.runs.map(runJson)),
      "meanTokens" -> stats.meanTokens,
      "medianTokens" -> stats.medianTokens,
      "variance" -> stats.variance,
      "meanInputTokens" -> stats.meanInputTokens,
      "meanOutputTokens" -> stats.meanOutputTokens,
      "meanCacheTokens" -> stats.meanCacheTokens
    )

  private def aggregate(rawText: String): String =
    val raw = ujson.read(rawText)
    val measurements = parseMeasurements(raw)
    val rows = measurements.sortBy(m => (m.id, m.engine)).map { measurement =>
      val withMcp = armStats(measurement, "with-mcp")
      val withoutMcp = armStats(measurement, "without-mcp")
      val delta = rounded(withoutMcp.meanTokens - withMcp.meanTokens)
      ujson.Obj(
        "id" -> measurement.id,
        "query" -> measurement.query,
        "tool" -> measurement.tool,
        "baseline" -> measurement.baseline,
        "engine" -> measurement.engine,
        "model" -> measurement.model,
        "withMcp" -> statsJson(withMcp),
        "withoutMcp" -> statsJson(withoutMcp),
        "tokenDelta" -> delta,
        "savingsPercent" -> savingsPercent(withoutMcp.meanTokens, withMcp.meanTokens)
      )
    }
    val withTotal = rounded(
      rows.map(row => row("withMcp")("meanTokens").num).sum
    )
    val withoutTotal = rounded(
      rows.map(row => row("withoutMcp")("meanTokens").num).sum
    )
    val delta = rounded(withoutTotal - withTotal)
    ujson
      .Obj(
        "schemaVersion" -> 1,
        "measurement" -> "end-to-end-agent-context",
        "source" -> raw("source"),
        "summary" -> ujson.Obj(
          "measurementCount" -> rows.size,
          "taskCount" -> rows.map(_("id").str).distinct.size,
          "engineCount" -> rows.map(_("engine").str).distinct.size,
          "toolMeanTokens" -> withTotal,
          "baselineMeanTokens" -> withoutTotal,
          "tokenDelta" -> delta,
          "savingsPercent" -> savingsPercent(withoutTotal, withTotal)
        ),
        "measurements" -> ujson.Arr.from(rows)
      )
      .render(indent = 2) + "\n"

  test("live token metrics sample aggregate matches raw run fixture") {
    val generated = aggregate(Files.readString(runsPath).nn)
    if sys.env.get("UPDATE_TOKEN_LIVE_METRICS").contains("1") then
      Files.writeString(outputPath, generated)
    else
      assertEquals(
        Files.readString(outputPath).nn,
        generated,
        "live token metrics aggregate is stale"
      )
  }
