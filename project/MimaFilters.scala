import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters.exclude

object MimaFilters {
  lazy val changes: Seq[ProblemFilter] = Seq(
    exclude[MissingClassProblem]("monix.bio.internal.*")
  )
}