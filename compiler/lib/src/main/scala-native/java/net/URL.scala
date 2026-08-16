package java.net

// Minimal java.net.URL shim for the Scala Native 0.5 build
//
// It fails loudly if ever actually reached.
class URL(spec: String) {
  def openStream(): java.io.InputStream =
    throw new UnsupportedOperationException(
      s"java.net.URL is not supported in the Scala Native build (URL: $spec)"
    )
}
