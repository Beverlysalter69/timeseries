package carldata.series

import java.time.{Duration, LocalDateTime, ZoneOffset}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}


object TimeSeries {

  /** Create TimeSeries from timestamps */
  def fromTimestamps[V: Numeric](rows: Seq[(Long, V)]): TimeSeries[V] = {
    new TimeSeries(rows.map(r => (LocalDateTime.ofEpochSecond(r._1, 0, ZoneOffset.UTC), r._2)))
  }

  /** Create empty series */
  def empty[V: Numeric]: TimeSeries[V] = {
    new TimeSeries[V](Seq[(LocalDateTime, V)]())
  }

  /** Return new series, add default value to missing points */
  def fillMissing[V: Numeric](xs: TimeSeries[V], delta: Duration, default: V)(implicit num: Fractional[V]): TimeSeries[V] = {
    def f(x1: (LocalDateTime, V), x2: (LocalDateTime, V), tsh: LocalDateTime) = default

    xs.resample(delta, f)
  }

  /** Return new series by interpolate missing points */
  def interpolate[V: Numeric](xs: TimeSeries[V], delta: Duration)(implicit num: Fractional[V]): TimeSeries[V] = {
    def f(x1: (LocalDateTime, V), x2: (LocalDateTime, V), tsh: LocalDateTime) = {
      val tx = num.fromInt(Duration.between(tsh, x1._1).toMillis.toInt)
      val ty = num.fromInt(Duration.between(tsh, x2._1).toMillis.toInt)
      num.plus(num.times(num.div(ty, num.plus(tx, ty)), x1._2), num.times(num.div(tx, num.plus(tx, ty)), x2._2))
    }

    xs.resample(delta, f)
  }

  /** Return new series with difference between 2 points */
  def differentiate[V: Numeric](ts: TimeSeries[V])(implicit num: Numeric[V]): TimeSeries[V] = {
    if (ts.isEmpty) ts
    else {
      val vs: Vector[V] = ts.values.zip(ts.values.tail).map(x => num.minus(x._2, x._1))
      TimeSeries(ts.index.tail, vs)
    }
  }

  /**
    * Return new series with difference between 2 points with the possibility of overflow value
    * Use case:
    * Older sensor equipment often used simple counters to record events.
    * These counters would continue to some max value (i.e. 100) and then rollover to 0
    */
  def diffOverflow[V: Numeric](ts: TimeSeries[V], overflowValue: V)(implicit num: Numeric[V]): TimeSeries[V] = {
    if (ts.isEmpty) ts
    else {
      val vs: Vector[V] = ts.values.zip(ts.values.tail).map { x =>
        if (num.lt(x._2, x._1)) num.minus(num.plus(x._2, overflowValue), x._1)
        else num.minus(x._2, x._1)
      }
      TimeSeries(ts.index, vs)
    }
  }

  /** Accumulate sum for each point */
  def integrate[V: Numeric](ts: TimeSeries[V])(implicit num: Numeric[V]): TimeSeries[V] = {
    if (ts.isEmpty) ts
    else {
      val vs: Vector[V] = ts.values.zip(ts.values.tail).map(x => num.plus(x._1, x._2))
      new TimeSeries(ts.index.tail, vs)
    }
  }

  /**
    * Integrate series for selected window.
    * Windows are not overlapping and sum starts at 0 at the beginning of each window
    */
  def integrateByTime[V: Numeric](ts: TimeSeries[V], windowSize: Duration)(implicit num: Numeric[V]): TimeSeries[V] = {
    if (ts.isEmpty) ts
    else {
      val end = ts.index.head.plus(windowSize)
      // This buffer could be used inside foldLeft, but then Intellij Idea will show wrong errors in += operation.
      val xs = ArrayBuffer.empty[V]
      ts.index.zip(ts.values).foldLeft[(V, LocalDateTime)]((num.zero, end)) { (acc, x) =>
        if (x._1.isBefore(acc._2)) {
          val v = num.plus(acc._1, x._2)
          xs += v
          (v, acc._2)
        } else {
          xs += x._2
          (x._2, acc._2.plus(windowSize))
        }
      }
      TimeSeries(ts.index, xs.toVector)
    }
  }

}

/**
  * TimeSeries contains data indexed by DateTime. The type of stored data
  * is parametric.
  */
case class TimeSeries[V](idx: Vector[LocalDateTime], ds: Vector[V]) {

  def this(d: Seq[(LocalDateTime, V)]) = {
    this(d.map(_._1).toVector, d.map(_._2).toVector)
  }

  val length: Int = math.min(idx.length, ds.length)
  val index: Vector[LocalDateTime] = idx.take(length)
  val values: Vector[V] = ds.take(length)

  /** Check is series is empty */
  def isEmpty: Boolean = index.isEmpty || values.isEmpty

  /** Safe get. If element is out of the bounds then 0 is returned */
  def get(i: Int)(implicit num: Numeric[V]): V = values.lift(i).getOrElse(num.zero)

  def head: Option[(LocalDateTime, V)] = {
    for {
      i <- index.headOption
      v <- values.headOption
    } yield (i, v)
  }

  /** Get last element of the series */
  def last: Option[(LocalDateTime, V)] = {
    for {
      i <- index.lastOption
      v <- values.lastOption
    } yield (i, v)
  }

  /** Filter by index and value */
  def filter(f: ((LocalDateTime, V)) => Boolean): TimeSeries[V] = {
    new TimeSeries(index.zip(values).filter(f))
  }

  /** Map by index and value. Create new values */
  def map(f: ((LocalDateTime, V)) => V): TimeSeries[V] = {
    val vs: Vector[V] = index.zip(values).map(f)
    new TimeSeries(index, vs)
  }

  /** Map over values. */
  def mapValues(f: V => V): TimeSeries[V] = {
    val vs: Vector[V] = values.map(f)
    new TimeSeries(index, vs)
  }

  /** Get slice of series with left side inclusive and right side exclusive
    * this operation is based on index.
    */
  def slice(start: LocalDateTime, end: LocalDateTime): TimeSeries[V] = {
    val d = index.zip(values).filter(x => (x._1.isAfter(start) || x._1.isEqual(start)) && x._1.isBefore(end))
    new TimeSeries(d)
  }

  /** Aggregate date by time */
  def groupByTime(g: LocalDateTime => LocalDateTime, f: Seq[V] => V): TimeSeries[V] = {
    if (isEmpty) this
    else {
      val xs = ListBuffer[(LocalDateTime, ArrayBuffer[V])]((g(index.head), ArrayBuffer()))
      for ((k, v) <- index.zip(values)) {
        val last = xs.last
        val t = g(k)
        if (last._1.isEqual(t)) last._2 += v
        else xs += ((t, ArrayBuffer(v)))
      }
      TimeSeries(xs.map(_._1).toVector, xs.map(x => f(x._2)).toVector)
    }
  }

  /**
    * Resample given TimeSeries with indexes separated by delta
    */
  def resample(delta: Duration, f: ((LocalDateTime, V), (LocalDateTime, V), LocalDateTime) => V)(implicit num: Numeric[V]): TimeSeries[V] = {
    if (index.isEmpty) TimeSeries.empty[V](num)
    else {
      val ys: mutable.ListBuffer[V] = ListBuffer()
      val ts = Iterator.iterate(index.head)(_.plusNanos(delta.toNanos))
        .takeWhile(_.isBefore(index.last.plusNanos(1))).toVector

      @tailrec def g(ts: Vector[LocalDateTime], xs: Vector[LocalDateTime], vs: Vector[V], prev: V): Unit = {
        val tsh = ts.head
        val xsh = xs.head
        if (xsh.isEqual(tsh)) {
          ys.append(vs.head)
          if (ts.size != 1) g(ts.tail, xs.tail, vs.tail, vs.head)
        }
        else if (tsh.isBefore(xsh)) {
          val ysh = if (ts.size != 1) ts.tail.head else xsh
          val mu = f((xsh, vs.head), (ysh, prev), tsh)
          ys.append(mu)
          if (ts.size != 1) g(ts.tail, xs, vs, mu)
        }
        else {
          g(ts, xs.tail, vs.tail, vs.head)
        }
      }

      g(ts, index, values, num.fromInt(0))
      TimeSeries(ts, ys.toVector)
    }
  }

  /** Rolling window operation */
  def rollingWindow(windowSize: Duration, f: Seq[V] => V): TimeSeries[V] = {

    @tailrec def g(v: Vector[(LocalDateTime, V)], out: Vector[V]): Vector[V] = {
      if (v.nonEmpty) {
        val splitIndex: Int = v.indexWhere(x => x._1.isBefore(v.head._1.minus(windowSize).minusNanos(1)))
        val window = if (splitIndex > 0) v.take(splitIndex) else v
        g(v.tail, out :+ f(window.map(x => x._2)))
      }
      else out
    }

    new TimeSeries(index, g(index.zip(values).reverse, Vector.empty).reverse)
  }

  /** Repeat series */
  def repeat(start: LocalDateTime, end: LocalDateTime, d: Duration): TimeSeries[V] = {
    val ts = slice(start, start.plus(d))
    if (ts.isEmpty) ts
    else {
      @tailrec def repeatR(offset: Duration, dp: (Vector[LocalDateTime], Vector[V])): (Vector[LocalDateTime], Vector[V]) = {
        val idx = ts.index.map(_.plus(offset))
        if (idx.head.isBefore(end)) repeatR(offset.plus(d), (dp._1 ++ idx, dp._2 ++ ts.values))
        else dp
      }

      val (idx, vs) = repeatR(Duration.ZERO, (Vector(), Vector()))
      TimeSeries(idx, vs)
    }
  }

  def shiftTime(d: Duration, forward: Boolean): TimeSeries[V] = {
    val idx = index.map(i => if (forward) i.plus(d) else i.minus(d))
    TimeSeries(idx, values)
  }
}

