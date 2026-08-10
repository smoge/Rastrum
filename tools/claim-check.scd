
(
var balanced, matches, instrument;


balanced = { |text|
    var depth = 0;
    text.do { |char|
        if ("([{".indexOf(char).notNil) { depth = depth + 1 };
        if (")]}".indexOf(char).notNil) { depth = depth - 1 };
    };
    depth == 0
};


matches = { |got, want|
    if (want.size > 1 and: {
        want.beginsWith("\"") and: { want.endsWith("\"") }
    }) {
        got == want
    } {
        got.reject { |char| char.isSpace } == want.reject { |char| char.isSpace }
    }
};

instrument = { |block, label|
    var out = [], claims = 0, rejected = [], pending = "", carry;

    carry = { |line|
        var at = line.find("//");
        var code = if (at.isNil) { line } { line.copyRange(0, at - 1) };
        pending = (pending ++ " " ++ code).stripWhiteSpace;
        if (pending.endsWith(";") and: { balanced.value(pending) }) { pending = "" };
    };

    block.do { |pair|
        var n = pair[0], line = pair[1], at = line.find("// ->"), code, claim;
        if (at.isNil) { out = out.add(line); carry.value(line) } {
            code = line.copyRange(0, at - 1).stripWhiteSpace;
            claim = line.copyToEnd(at + 5).stripWhiteSpace;
            if (code.endsWith(";")
                and: { balanced.value(code) }
                and: { code.beginsWith(".").not }
                and: { code.beginsWith("var ").not }
                and: { pending.isEmpty }) {
                claims = claims + 1;
                out = out.add("~rastrumClaim.value((%), \"%\", \"%\", %);".format(
                    code.drop(-1), claim.replace("\"", "\\\""), label, n));
            } {
                rejected = rejected.add([n, line.stripWhiteSpace]);
                out = out.add(line);
                carry.value(line);
            }
        }
    };
    IdentityDictionary[\lines -> out, \claims -> claims, \rejected -> rejected]
};

IdentityDictionary[
    \balanced -> balanced, \matches -> matches, \instrument -> instrument]
)
