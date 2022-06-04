package com.raphtory.api.graphview

import com.raphtory.client.QuerySender
import com.raphtory.components.querymanager.Query
import com.typesafe.config.Config

/** Root class for local deployments of the analysis API
  *
  * A DeployedTemporalGraph is a [[TemporalGraph]]
  * with a [[Deployment]] object attached to it that allows to stop it.
  *
  * @param deployment The deployment object used to control Raphtory
  *
  * @see [[TemporalGraph]], [[Deployment]], [[com.raphtory.deployment.Raphtory]]
  */
class DeployedTemporalGraph private[raphtory] (
    override private[api] val query: Query,
    override private[api] val querySender: QuerySender,
    val deployment: Deployment,
    override private[api] val conf: Config
) extends TemporalGraph(query, querySender, conf) {}