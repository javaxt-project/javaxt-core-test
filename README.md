# JavaXT Core Test Project

This project is used to test the [javaxt-core](https://github.com/javaxt-project/javaxt-core) library,
providing test coverage for various components and utilities.

The project consists of two main modules that work together to test the javaxt-core library:

1. javaxt-core-build: Builds the javaxt-core library using sources from a local directory or GitHub
2. javaxt-core-test: Runs test scripts

## Building the Project

### Build All Modules (Default Branch)
```bash
mvn clean install
```

### Build with Specific Branch
```bash
# Build using a specific branch (e.g., 'dev')
mvn clean install -Djavaxt.core.branch=dev

# Build using a feature branch
mvn clean install -Djavaxt.core.branch=feat/54

# Build using a specific commit/tag
mvn clean install -Djavaxt.core.branch=v1.11.3
```

### Build with Local javaxt-core Directory
```bash
# Build using local javaxt-core source directory
mvn clean install -Djavaxt.core.local.dir=/path/to/javaxt-core/src

# Example with Windows path (point to src directory)
mvn clean install -Djavaxt.core.local.dir=C:\path\to\javaxt-core\src

```

**Note**: The local directory should point to the source directory containing the
javaxt-core Java files (typically the `src` directory), not necessarily a Maven project.
The files will be copied and compiled directly in the test project.

## Running Tests

### Run All Tests (Default Branch)
```bash
mvn test
```

### Run Tests with Specific Branch
```bash
# Run tests against a specific branch
mvn test -Djavaxt.core.branch=dev

# Run tests against a feature branch
mvn test -Djavaxt.core.branch=feat/54
```

### Run Tests with Local javaxt-core Directory
```bash
# Run tests against local javaxt-core source
mvn test -Djavaxt.core.local.dir=/path/to/javaxt-core/src

# Example with Windows path (point to src directory)
mvn test -Djavaxt.core.local.dir=C:\path\to\javaxt-core\src
```

## Command Line Interface

A jar file is created during the "package" phase and can be used to interactively
test javaxt-core features.

```bash
# Build jar file (See above for more options)
mvn clean package

# Execute tests via command line
java -jar dist/javaxt-core-test.jar -test ...
```


## Contributing

When adding new tests:
1. Place test classes in the appropriate package under `javaxt-core-test/src/test/java/`
2. Follow the existing naming conventions
3. Include comprehensive test coverage for edge cases
4. Add any additional resources to `javaxt-core-test/src/main/resources/`
