/*
Runs `// >>>` examples in source comments.

Usage:

  sclang tools/doctest.scd [dir ...]

*/

(
var targets = thisProcess.argv.copy;
var files, fileCount = 0, exampleCount = 0, bad = 0;
var check, matches, examplesOf, balanced, here;

here = thisProcess.nowExecutingPath.dirname;
// Format-specific scan, shared comparison.
check = File.readAllString(here +/+ "claim-check.scd").compile.value;
balanced = check[\balanced];
matches = check[\matches];
examplesOf = File.readAllString(here +/+ "doctest-scan.scd")
    .compile.value.value(balanced);

if (targets.isEmpty) {
    var root = thisProcess.nowExecutingPath.dirname.dirname;
    var classes = root +/+ "Classes";
    targets = [if (File.exists(classes)) { classes } { root }];
};

targets.do { |dir|
    if (File.exists(dir).not) {
        "doctest: no directory at %".format(dir).error;
        1.exit;
    };
};

files = targets.collect { |dir|
    PathName(dir).deepFiles
        .select { |file| file.extension == "sc" }
        .collect { |file| file.fullPath }
}.flatten.sort;

if (files.isEmpty) {
    "doctest: no .sc files under %".format(targets.join(", ")).error;
    1.exit;
};

files.do { |path|
    var name = path.basename;
    var examples = examplesOf.value(path);

    if (examples.notEmpty) {
        var failuresBefore = bad;
        fileCount = fileCount + 1;
        Environment.new.use {
            examples.do { |example|
                var lineNo = example[0], expr = example[1], want = example[2];
                var func = expr.compile;
                var got, error;

                exampleCount = exampleCount + 1;
                case
                { want.isEmpty } {
                    bad = bad + 1;
                    "FAIL %:% - no answer given".format(name, lineNo).postln;
                    "       >>> %".format(expr).postln;
                }
                { func.isNil } {
                    bad = bad + 1;
                    "FAIL %:% - did not compile".format(name, lineNo).postln;
                    "       >>> %".format(expr).postln;
                }
                {
                    try { got = func.value } { |err| error = err };
                    case
                    { error.notNil } {
                        bad = bad + 1;
                        "FAIL %:% - threw: %".format(
                            name, lineNo, error.errorString).postln;
                        "       >>> %".format(expr).postln;
                    }
                    { matches.value(got.asString, want).not } {
                        bad = bad + 1;
                        "FAIL %:% - answered otherwise".format(
                            name, lineNo).postln;
                        "       >>> %".format(expr).postln;
                        "       expected %".format(want).postln;
                        "       got      %".format(got.asString).postln;
                    };
                };
            };
        };
        if (bad == failuresBefore) {
            "ok   % - % examples".format(name, examples.size).postln;
        };
    };
};

"".postln;
"% files, % examples, % failures".format(fileCount, exampleCount, bad).postln;
if (bad == 0) { 0.exit } { 1.exit };
)
