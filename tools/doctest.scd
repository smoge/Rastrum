
(
var targets = thisProcess.argv.copy;
var files, fileCount = 0, exampleCount = 0, bad = 0;
var check, matches, examplesOf, balanced;


check = File.readAllString(
    thisProcess.nowExecutingPath.dirname +/+ "claim-check.scd").compile.value;
balanced = check[\balanced];
matches = check[\matches];

if (targets.isEmpty) {
    var root = thisProcess.nowExecutingPath.dirname.dirname;
    var classes = root +/+ "Classes";
    targets = [if (File.exists(classes)) { classes } { root }];
};


examplesOf = { |path|
    var lines = File.readAllString(path).split($\n);
    var found = [], commentAt;

    commentAt = { |i|
        var text = (lines[i] ? "").stripWhiteSpace;
        if (text.beginsWith("//")) { text.drop(2).stripWhiteSpace } { nil }
    };

    lines.do { |line, i|
        var body = commentAt.value(i);
        if (body.notNil and: { body.beginsWith(">>>") }) {
            var arrows, cut, expr, want, next, last = i;
            body = body.drop(3).stripWhiteSpace;
            while {
                var ahead = commentAt.value(last + 1);
                ahead.notNil
                and: { ahead.notEmpty }
                and: { ahead.beginsWith(">>>").not }
                and: { balanced.value(body).not or: { ahead.beginsWith(".") } }
            } {
                last = last + 1;
                body = body ++ " " ++ commentAt.value(last);
            };
            arrows = body.findAll("->") ? [];
            arrows.reverseDo { |at|
                var left = body.copyRange(0, at - 1);
                if (cut.isNil and: { balanced.value(left) }) { cut = at };
            };
            if (cut.notNil) {
                expr = body.copyRange(0, cut - 1);
                want = body.copyToEnd(cut + 2);
            } {
                next = commentAt.value(last + 1);
                expr = body;
                want = "";
                if (next.notNil and: {
                    next.notEmpty and: { next.beginsWith(">>>").not }
                }) {
                    want = next
                };
            };
            found = found.add(
                [i + 1, expr.stripWhiteSpace, want.stripWhiteSpace]);
        };
    };
    found
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
