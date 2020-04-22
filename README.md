# Vincent

## How to build
Vincent is using [Wemi](https://github.com/Darkyenus/wemi) to build.
Wemi in turn requires a POSIX compatible shell - Linux and macOS both have POSIX compatible shells out of the box.
On Windows it is possible to use [WSL](https://en.wikipedia.org/wiki/Windows_Subsystem_for_Linux),
[Cygwin](https://cygwin.com/) or [MSYS/MSYS2](https://en.wikipedia.org/wiki/MinGW).
Perhaps the easiest way to get a POSIX shell on Windows is to use the one that is bundled with [git](https://gitforwindows.org/).

Wemi does not need any installation and can run out of the box, just run `./wemi` in this directory.

To run locally, run `./wemi run`, or issue `run` command in the interactive Wemi prompt (this gives much faster compile times for repeated launches and can be done for all wemi commands).

To create a distribution package, run `./wemi assembly`. The `build/launcher.sh` file serves as an example on how to run the resulting jar on a production server.

Debugging is possible by running `./wemi debug:run` and [connecting with debugger](https://www.jetbrains.com/help/idea/run-debug-configuration-remote-debug.html).

`./wemi test` runs unit tests.

Wemi also has an [IntelliJ plugin](https://plugins.jetbrains.com/plugin/12716-wemi), which is highly recommended as it can import the project correctly into the IDE and integrates with the built-in debugging support.

## Internal architecture
- `/src/`: Source and resources for use directly from the code, including tests, in a standard Maven-like layout
- `/resources/`: Resources accessible through the web-server, including images, CSS, JavaScript, and other static files
- `/docs/`: Some extra documentation which does not relate to code directly
- `/build/`: Build script (`build.kt`) and build auxiliary files

Overview of the source files:
- `Main.kt`: The entry-point, argument parsing, command handling, etc.
- `DatabaseSchema.kt`: Definition of the database schema and some utility methods tightly related to it
- `Session.kt`: User login session keeping, creation, destruction, rate-limiting, etc.
- `template/`: Parsing and representation of questionnaire templates
- `pages/`: Presentation and logic of all "web screens". The routing is established through `fun RoutingHandler.setup_SOMETHING_Routes()` methods.

The application uses the traditional web model of hyperlinks (i.e. it is not a single page application) and uses minimal JavaScript only for essential operations.
Thanks to this, it is extremely light on browsers. All visual effects are done in CSS, for higher performance and graceful fallback on old devices.
JS scripts are used only when the functionality would be impossible to implement without it, or when adding scripts greatly improves user experience.
A lot of effort is put into making the application usable without JavaScript wherever possible.

## Technologies
When hacking on the project, familiarity with following technologies is expected:
- [Kotlin](https://kotlinlang.org/): The main language used on the server side (not used on client-side, because of the added overhead). Experience with Java and its ecosystem is also useful.
- [Undertow](http://undertow.io/): The embedded web server
- [SQL](https://en.wikipedia.org/wiki/SQL): Embedded SQL database [H2](https://h2database.com/) is used for data storage
    - [Exposed](https://github.com/JetBrains/Exposed): Used for typed SQL bindings
- [HTML, CSS and JavaScript](https://developer.mozilla.org/en-US/): As this is, after all, a web application

Several other minor libraries are used, see [the build script](build/build.kt),
but they are not necessary to understand unless working on code that directly uses them.

## License
This project is licensed under [Mozilla Public License Version 2.0](https://choosealicense.com/licenses/mpl-2.0/).

*Copyright 2019-2020 Jan Polák*
