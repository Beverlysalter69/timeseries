package carldata.series

import java.time.Instant

import org.scalatest._


class CsvTest extends FlatSpec with Matchers {

  "Csv reader" should "read single series from string" in {
    val str =
      """time,value
        |2005-01-01T12:34:15,2
        |2006-01-01T12:34:15,-4
        |2007-01-01T12:34:15,-6
        |2008-01-01T12:34:15,9""".stripMargin
    val series = Csv.fromString(str).head
    series.values shouldBe Vector(2, -4, -6, 9)
  }

  it should "read many series from string" in {
    val str =
      """time,value1,value2,value3
        |2005-01-01T12:34:15,2,3,1
        |2006-01-01T12:34:15,-4,-1,1
        |2007-01-01T12:34:15,-6,0,0
        |2008-01-01T12:34:15,9,2,1""".stripMargin

    val idx: Vector[Instant] = Vector(Instant.parse("2005-01-01T12:34:15Z"), Instant.parse("2006-01-01T12:34:15Z")
      , Instant.parse("2007-01-01T12:34:15Z"), Instant.parse("2008-01-01T12:34:15Z"))

    val vs1: Vector[Double] = Vector(2, -4, -6, 9)
    val vs2: Vector[Double] = Vector(3, -1, 0, 2)
    val vs3: Vector[Double] = Vector(1, 1, 0, 1)
    val expected = Seq(TimeSeries(idx, vs1), TimeSeries(idx, vs2), TimeSeries(idx, vs3))
    val series = Csv.fromString(str)
    series shouldBe expected
  }

  it should "read many series with gaps from string" in {
    val idx@Vector(idx0, idx1, idx2, idx3) = Vector(
      "2005-01-01T12:34:15Z",
      "2006-01-01T12:34:15Z",
      "2007-01-01T12:34:15Z",
      "2008-01-01T12:34:15Z"
    )

    val str =
      s"""time,value1,value2,value3
         |$idx0,2,,1
         |$idx1,,-1,1
         |$idx2,,0,0
         |$idx3,,2,1""".stripMargin

    val expected = Seq(
      Vector((idx0, 2)),
      Vector((idx1, -1), (idx2, 0), (idx3, 2)),
      Vector((idx0, 1), (idx1, 1), (idx2, 0), (idx3, 1))
    ).map { vs =>
      val ts = vs.map { case (i, x) => (Instant.parse(i).getEpochSecond, x) }
      TimeSeries.fromTimestamps(ts)
    }
    val series = Csv.fromString(str)
    series shouldBe expected
  }

  it should "read custom time format" in {
    val str =
      """time,value
        |2013-09-09 10:50:00,2.805
        |2013-09-09 10:55:00,2.796
        |2013-09-09 11:00:00,2.791
        |2013-09-09 11:05:00,46.68
        |2013-09-09 11:10:00,48.03""".stripMargin
    val series = Csv.fromString(str).head
    series.values shouldBe Vector(2.805, 2.796, 2.791, 46.68, 48.03)
  }

  it should "read custom time format 2" in {
    val str =
      """time,value
        |2005-01-01,2
        |2006-01-01,-4
        |2007-01-01,-6
        |2008-01-01,9""".stripMargin
    val series = Csv.fromString(str).head
    series.values shouldBe Vector(2, -4, -6, 9)
  }

  "CSV Writer" should "save series to string" in {
    val now = Instant.ofEpochSecond(1000)
    val idx = Vector(now, now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3))
    val series = TimeSeries(idx, Vector(1f, 4f, 6f, 9f))
    val csv = Csv.toCsv(series)
    series shouldBe Csv.fromString(csv).head
  }

  it should "save and load complex series" in {
    val idx@Vector(idx0, idx1, idx2, idx3) = Vector(
      "2005-01-01T12:34:15Z",
      "2006-01-01T12:34:15Z",
      "2007-01-01T12:34:15Z",
      "2008-01-01T12:34:15Z"
    )

    val str =
      s"""time,value1,value2,value3
         |$idx0,2,,1
         |$idx1,,-1,1
         |$idx2,,0,0
         |$idx3,,2,1""".stripMargin

    val insts@Seq(inst0, inst1, inst2, inst3) = idx.map(i => Instant.parse(i))
    val input = Seq(
      Seq((inst0, 2)),
      Seq((inst1, -1), (inst2, 0), (inst3, 2)),
      Seq((inst0, 1), (inst1, 1), (inst2, 0), (inst3, 1))
    ).map { vs =>
      val ts = vs.map { case (i, x) => (i.getEpochSecond, x) }
      TimeSeries.fromTimestamps(ts)
    }

    val expected = Seq(
      TimeSeries(insts, Vector(2, 0, 0, 0)),
      TimeSeries(insts, Vector(0, -1, 0, 2)),
      TimeSeries(insts, Vector(1, 1, 0, 1))
    )

    Csv.fromString(Csv.toComplexCsv(input)) shouldBe expected
  }

}