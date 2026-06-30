// Add SLF4J NOP binding to silence the StaticLoggerBinder warning during sbt initialization.
// The warning occurs because io.get-coursier:interface pulls in org.slf4j:slf4j-api, and without
// a binding implementation, SLF4J defaults to NOP and prints a warning to stderr.
libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-nop" % "1.7.36"
)
