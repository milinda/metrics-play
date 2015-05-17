/*
* Copyright 2013 Kenshoo.com
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.kenshoo.play.metrics

import java.util.concurrent.TimeUnit

import ch.qos.logback.classic
import com.codahale.metrics.graphite.{Graphite, GraphiteSender, GraphiteReporter}
import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics.logback.InstrumentedAppender
import com.codahale.metrics.{MetricFilter, JvmAttributeGaugeSet, MetricRegistry, SharedMetricRegistries}
import com.fasterxml.jackson.databind.ObjectMapper
import play.api.{Application, Logger, Play, Plugin}


object MetricsRegistry {

  def defaultRegistry = Play.current.plugin[MetricsPlugin] match {
    case Some(plugin) => SharedMetricRegistries.getOrCreate(plugin.registryName)
    case None => throw new Exception("metrics plugin is not configured")
  }

  @deprecated(message = "use defualtRegistry")
  def default = defaultRegistry
}


class MetricsPlugin(val app: Application) extends Plugin {
  val validUnits = Some(Set("NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS"))

  val mapper: ObjectMapper = new ObjectMapper()

  def registryName = app.configuration.getString("metrics.name").getOrElse("default")

  implicit def stringToTimeUnit(s: String): TimeUnit = TimeUnit.valueOf(s)

  override def onStart() {
    def setupJvmMetrics(registry: MetricRegistry) {
      val jvmMetricsEnabled = app.configuration.getBoolean("metrics.jvm").getOrElse(true)
      if (jvmMetricsEnabled) {
        registry.register("jvm.attribute", new JvmAttributeGaugeSet())
        registry.register("jvm.gc", new GarbageCollectorMetricSet())
        registry.register("jvm.memory", new MemoryUsageGaugeSet())
        registry.register("jvm.threads", new ThreadStatesGaugeSet())
      }
    }

    def setupLogbackMetrics(registry: MetricRegistry) = {
      val logbackEnabled = app.configuration.getBoolean("metrics.logback").getOrElse(true)
      if (logbackEnabled) {
        val appender: InstrumentedAppender = new InstrumentedAppender(registry)

        val logger: classic.Logger = Logger.logger.asInstanceOf[classic.Logger]
        appender.setContext(logger.getLoggerContext)
        appender.start()
        logger.addAppender(appender)
      }
    }

    def setupGraphiteMetrics(registry: MetricRegistry, rateUnit: String, durationUnit: String) = {
      val graphiteEnabled = app.configuration.getBoolean("metrics.graphite.enabled").getOrElse(false)
      if (graphiteEnabled) {
        val graphitePeriod = app.configuration.getInt("metrics.graphite.period").getOrElse(1)
        val graphiteUnit = app.configuration.getString("metrics.graphite.unit").getOrElse("MINUTES")
        val graphiteHost = app.configuration.getString("metrics.graphite.host").getOrElse("localhost")
        val graphitePort = app.configuration.getInt("metrics.graphite.port").getOrElse(2003)
        val graphitePrefix = app.configuration.getString("metrics.graphite.prefix").getOrElse("metrics.graphite")
        val graphiteRateUnit = app.configuration.getString("metrics.graphite.rateUnit").getOrElse(rateUnit)
        val graphiteDurationUnit = app.configuration.getString("metrics.graphite.durationUnit").getOrElse(durationUnit)

        val graphite = new Graphite(graphiteHost, graphitePort)
        val graphiteReporter = GraphiteReporter.forRegistry(registry)
        .prefixedWith(graphitePrefix)
        .convertRatesTo(TimeUnit.valueOf(graphiteRateUnit))
        .convertDurationsTo(TimeUnit.valueOf(graphiteDurationUnit))
        .filter(MetricFilter.ALL)
        .build(graphite)

        graphiteReporter.start(graphitePeriod, TimeUnit.valueOf(graphiteUnit))
      }
    }

    if (enabled) {
      val registry: MetricRegistry = SharedMetricRegistries.getOrCreate(registryName)
      val rateUnit = app.configuration.getString("metrics.rateUnit", validUnits).getOrElse("SECONDS")
      val durationUnit = app.configuration.getString("metrics.durationUnit", validUnits).getOrElse("SECONDS")
      val showSamples = app.configuration.getBoolean("metrics.showSamples").getOrElse(false)

      setupJvmMetrics(registry)
      setupLogbackMetrics(registry)
      setupGraphiteMetrics(registry, rateUnit, durationUnit)

      val module = new MetricsModule(rateUnit, durationUnit, showSamples)
      mapper.registerModule(module)
    }
  }


  override def onStop() {
    if (enabled) {
      SharedMetricRegistries.remove(registryName)
    }
  }

  override def enabled = app.configuration.getBoolean("metrics.enabled").getOrElse(true)
}

