import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters.exclude

object MimaFilters {
  lazy val changes: Seq[ProblemFilter] = Seq(
    exclude[MissingClassProblem]("monix.bio.internal.*"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Context.copy"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Context.this"),
    exclude[IncompatibleMethTypeProblem]("monix.bio.IO#Context.apply"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Context.apply"),
    exclude[IncompatibleMethTypeProblem]("monix.bio.IO#Map.apply"),
    exclude[IncompatibleMethTypeProblem]("monix.bio.IO#Map.this"),
    exclude[IncompatibleResultTypeProblem]("monix.bio.IO#Map.copy$default$3"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Map.index"),
    exclude[IncompatibleMethTypeProblem]("monix.bio.IO#Map.copy"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#FlatMap.apply"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#FlatMap.this"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#FlatMap.copy"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Async.apply"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Async.copy"),
    exclude[DirectMissingMethodProblem]("monix.bio.IO#Async.this")
  )
}
