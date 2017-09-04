package carldata.series

/**
  * Created by Krzysztof Langner on 2017-03-25.
  */
object Stats {

  implicit class SeriesStats[T](val series: TimeSeries[T])(implicit num: Numeric[T]) {

    def mean: Double = num.toDouble(series.values.sum) / series.length

    def variance: Double = {
      val m = mean
      val s = series.values
        .map(num.toDouble)
        .map(x => Math.pow(x-m, 2))
        .sum
      s / series.length
    }

    def stddev: Double = Math.sqrt(variance)
  }

}
