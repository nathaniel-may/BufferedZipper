# BufferedZipper

BufferedZipper is a data type that manages effectful buffering with simple configuration.

Buffer size can be limited by either the number of elements or by the in-memory size of the elements. In order for the zipper to remain functional with buffer sizes of 0 the focus is _not_ included in measurements of buffers.  

README TODO:
- Describe why cats vs scalaz (cats doesn't have a zipper) [this issue](https://github.com/typelevel/cats/issues/1156)

Current Work:
- Cleaner way to implement BufferedWindow
- Add the tests that are currently TODOs
- Add examples to README with unit tests that show they work

Feature Ideas:
- SLF4J log when running with byte limited buffers
- Optional number of elements to buffer ahead of focus on a separate thread.
- scalajs compatibility