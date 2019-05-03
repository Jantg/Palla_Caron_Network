name := "Dynamic_Network"

version := "0.1"

scalaVersion := "2.11.8"

cancelable in Global := true

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "2.4.0",
  "org.apache.spark" %% "spark-sql" % "2.4.0",
  "org.apache.spark" %% "spark-mllib" % "2.4.0" % "runtime",
  // Last stable release
  "org.scalanlp" %% "breeze" % "0.13.2",
  "colt" % "colt" % "1.2.0",
  "net.sf.jung" % "jung-io" % "2.0.1",
  "net.sf.jung" % "jung-api" % "2.0.1",
  "net.sf.jung" % "jung-graph-impl" % "2.0.1",
  "net.sf.jung" % "jung-algorithms" % "2.0.1",
  "org.apache.commons" % "commons-collections4" % "4.1",
  "org.apache.commons" % "commons-math3" % "3.6.1",
// Native libraries are not included by default. add this if you want them (as of 0.7)
  // Native libraries greatly improve performance, but increase jar sizes.
  // It also packages various blas implementations, which have licenses that may or may not
  // be compatible with the Apache License. No GPL code, as best I know.
  "org.scalanlp" %% "breeze-natives" % "0.13.2"
)