# rand-pix

Refactoring
- Rename repo and project to BufferedZipper
- Cleaner way to implement BufferedWindow
- Add the tests that are currently TODOs
- Refactor test helpers into something less horrible

Feature Ideas:
- Support limiting buffer by number of items not just size
- Run without jvm plugin for measuring in bytes. If they don't use that feature, nothing bad happens. If they DO, log loud SLF4J lines with instructions for how install and run.
- Optional number of elements to buffer ahead of focus on a separate thread. 