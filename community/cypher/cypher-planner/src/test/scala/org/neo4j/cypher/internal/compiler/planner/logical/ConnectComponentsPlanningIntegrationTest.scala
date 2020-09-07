/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.compiler.planner.BeLikeMatcher.beLike
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.compiler.planner.logical.idp.cartesianProductsOrValueJoins.COMPONENT_THRESHOLD_FOR_CARTESIAN_PRODUCT
import org.neo4j.cypher.internal.expressions.Ands
import org.neo4j.cypher.internal.expressions.CachedProperty
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.ir.RegularSinglePlannerQuery
import org.neo4j.cypher.internal.logical.plans.AllNodesScan
import org.neo4j.cypher.internal.logical.plans.Apply
import org.neo4j.cypher.internal.logical.plans.CacheProperties
import org.neo4j.cypher.internal.logical.plans.CartesianProduct
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.logical.plans.NodeByLabelScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexScan
import org.neo4j.cypher.internal.logical.plans.NodeIndexSeek
import org.neo4j.cypher.internal.logical.plans.RangeQueryExpression
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.SingleQueryExpression
import org.neo4j.cypher.internal.logical.plans.ValueHashJoin
import org.neo4j.cypher.internal.planner.spi.IndexOrderCapability
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class ConnectComponentsPlanningIntegrationTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  test("should build cartesian product with sorted plan left for many disconnected components") {
    val nodes = (0 until COMPONENT_THRESHOLD_FOR_CARTESIAN_PRODUCT).map(i => s"(n$i:Few)").mkString(",")
    val orderedNode = s"n${COMPONENT_THRESHOLD_FOR_CARTESIAN_PRODUCT}"

    val plan = new given {
      labelCardinality = Map("Few" -> 1.0, "Many" -> 1000.0)
      indexOn("Many", "prop").providesOrder(IndexOrderCapability.BOTH)
    }.getLogicalPlanFor(s"MATCH $nodes, ($orderedNode:Many) WHERE exists($orderedNode.prop) RETURN * ORDER BY $orderedNode.prop")._2

    // We do not want a Sort
    plan shouldBe a[CartesianProduct]
    // Sorted index should be placed on the left of the cartesian products
    plan.leftmostLeaf should beLike {
      case NodeIndexScan(`orderedNode`, _, _, _, _) => ()
    }
  }

  test("should plan cartesian product for disconnected components") {
    planFor("MATCH (n), (m) RETURN n, m")._2 should equal(
      CartesianProduct(
        AllNodesScan("n", Set.empty),
        AllNodesScan("m", Set.empty)
      )
    )
  }

  test("should plan cartesian product so the cheaper plan is on the left") {
    (new given {
      cost = {
        case (_: Selection, _, _) => 1000.0
        case (_: NodeByLabelScan, _, _) => 20.0
      }
      cardinality = mapCardinality {
        case RegularSinglePlannerQuery(queryGraph, _, _, _, _) if queryGraph.selections.predicates.size == 1 => 10
      }
    } getLogicalPlanFor  "MATCH (n), (m) WHERE n.prop = 12 AND m:Label RETURN n, m")._2 should beLike {
      case CartesianProduct(_: Selection, _: NodeByLabelScan) => ()
    }
  }

  test("should plan cartesian product of three plans so the cost is minimized") {
    implicit val plan = new given {
      labelCardinality = Map(
        "A" -> 30.0,
        "B" -> 20.0,
        "C" -> 10.0
      )
    } getLogicalPlanFor "MATCH (a), (b), (c) WHERE a:A AND b:B AND c:C RETURN a, b, c"

    // C is cheapest so it should be furthest to the left, followed by B and A
    plan._2 should equal(
      CartesianProduct(
        NodeByLabelScan("c", labelName("C"), Set.empty, IndexOrderNone),
        CartesianProduct(
          NodeByLabelScan("b", labelName("B"), Set.empty, IndexOrderNone),
          NodeByLabelScan("a", labelName("A"), Set.empty, IndexOrderNone)
        )
      )
    )
  }

  test("should plan cartesian product of two plans so the cost is minimized") {
    implicit val plan = new given {
      labelCardinality = Map(
        "A" -> 30.0,
        "B" -> 20.0
      )
    } getLogicalPlanFor "MATCH (a), (b) WHERE a:A AND b:B RETURN a, b"

    // A x B = 30 * 2 + 30 * (20 * 2) => 1260
    // B x A = 20 * 2 + 20 * (30 * 2) => 1240

    plan._2 should equal(
      CartesianProduct(
        NodeByLabelScan("b", labelName("B"), Set.empty, IndexOrderNone),
        NodeByLabelScan("a", labelName("A"), Set.empty, IndexOrderNone)
      )
    )
  }

  test("should not plan apply with independent rhs") {
    val plan = (new given {
      indexOn("Awesome", "prop1")
      indexOn("Awesome", "prop2")
    } getLogicalPlanFor "MATCH (n:Awesome), (m:Awesome) WHERE n.prop1 < 42 AND m.prop2 < 42 AND n.prop1 = m.prop2 RETURN n")._2

    plan shouldNot beLike {
      case Selection(_, Apply(_, NodeIndexSeek(_, _, _, _, args, _))) if args.isEmpty => ()
    }
  }

  test("should plan nested index join or value hash join where rhs depends on lhs") {
    val plan = (new given {
      indexOn("Awesome", "prop1")
      indexOn("Awesome", "prop2")
      indexOn("Awesome", "prop3")
    } getLogicalPlanFor "MATCH (n:Awesome), (m:Awesome) WHERE n.prop1 < 42 AND m.prop2 < 42 AND n.prop3 = m.prop4 RETURN n")._2

    val beSolvedByApply = beLike {
      case Selection(_, Apply(
      NodeIndexSeek(_,_,_,RangeQueryExpression(_),_,_),
      NodeIndexSeek(_,_,_,SingleQueryExpression(_),_,_))) => ()
    }
    val beSolvedByJoin = beLike {
      case ValueHashJoin(
      NodeIndexSeek(_,_,_,RangeQueryExpression(_),_,_),
      NodeIndexSeek(_,_,_,RangeQueryExpression(_),_,_), _) => ()
    }

    plan should (beSolvedByApply or beSolvedByJoin)
  }

  test("should plan value hash join where rhs depends on lhs and there are no indexes") {
    val plan = planFor("MATCH (n), (m) WHERE n.prop1 = m.prop2 RETURN n")._2

    plan should beLike {
      case ValueHashJoin(
      AllNodesScan(_, _),
      AllNodesScan(_, _), _) => ()
    }
  }

  test("should plan value hash join with the cheapest plan on the left") {
    val plan = new given {
      labelCardinality = Map(
        "N" -> 30.0,
        "M" -> 20.0
      )
    }.getLogicalPlanFor("MATCH (n:N), (m:M) WHERE n.prop1 = m.prop1 RETURN n")._2

    plan should beLike {
      case ValueHashJoin(
      NodeByLabelScan("m", _, _, _),
      NodeByLabelScan("n", _, _, _), _) => ()
    }
  }

  test("should plan value hash join for the most selective predicate") {
    val equals1 = equals(prop("n", "prop1"), prop("m", "prop1"))
    val equals2 = equals(prop("n", "prop2"), prop("m", "prop2"))
    val equals1cached = equals(cachedNodeProp("n", "prop1"), cachedNodeProp("m", "prop1"))
    val equals2switched = equals2.switchSides

    val selectivities = Map[Expression, Double](
      hasLabels("n", "N") -> 1.0,
      hasLabels("m", "M") -> 0.5,
      equals1 -> 0.6,
      equals2 -> 0.4,
    )

    val plan = new given {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.connectedComponents.size))
    }.getLogicalPlanFor("MATCH (n:N), (m:M) WHERE n.prop1 = m.prop1 AND n.prop2 = m.prop2 RETURN n")._2

    // TODO Continue here Tobias
    plan should beLike {
      case Selection(Ands(Seq(`equals1cached`)),
      ValueHashJoin(
        CacheProperties(NodeByLabelScan("m", _, _, _), _),
        CacheProperties(NodeByLabelScan("n", _, _, _), _),
        `equals2switched`)
      ) => ()
    }
  }

  test("should plan value hash join for three components and there are no indexes") {
    val equals1 = equals(prop("n", "prop1"), prop("m", "prop1"))
    val equals2 = equals(prop("m", "prop2"), prop("o", "prop2"))
    val equals2switched = equals2.switchSides

    val selectivities = Map[Expression, Double](
      hasLabels("n", "N") -> 1.0,
      hasLabels("m", "M") -> 0.5,
      hasLabels("o", "O") -> 0.4,
      equals1 -> 0.6,
      equals2 -> 0.4,
    )

    val plan = new given {
      cardinality = selectivitiesCardinality(selectivities, qg => Math.pow(100.0, qg.connectedComponents.size))
    }.getLogicalPlanFor("MATCH (n:N), (m:M), (o:O) WHERE n.prop1 = m.prop1 AND m.prop2 = o.prop2 RETURN n")._2

    plan should beLike {
      case ValueHashJoin(
        NodeByLabelScan("n", _, _, _), // n as the single component should be on the left when joined with 2 components
        ValueHashJoin(
          NodeByLabelScan("o", _, _, _), // o should be on the left, its cheaper than m
          NodeByLabelScan("m", _, _, _),
        `equals2switched`), // connecting m and o is cheaper than connecting n and m
      `equals1`) => ()
    }
  }
}