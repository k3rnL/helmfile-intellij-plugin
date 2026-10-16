package dev.helmfile.intellij;

import com.intellij.openapi.util.text.StringUtil;
import java.util.*;

public final class FunctionCatalog {
    public record Function(String name, String signature, String description, String example, String url, String family) {
        public String html() {
            return "<div class='definition'><pre>" + escape(signature) + "</pre></div><div class='content'><p>"
                    + escape(description) + "</p><pre>" + escape("{{ " + example + " }}")
                    + "</pre><p>" + family + " · <a href='" + url + "'>Reference</a></p></div>";
        }
    }
    private static final Map<String, Function> FUNCTIONS = new LinkedHashMap<>();
    static {
        add("Helmfile", "https://helmfile.readthedocs.io/en/stable/templating_funcs/", """
                env|env name → string|Read an optional environment variable.|env "REGION" | default "eu-west-1"
                requiredEnv|requiredEnv name → string|Read an environment variable; rendering fails if it is empty.|requiredEnv "IMAGE_TAG"
                readFile|readFile path → string|Read a local file relative to the Helmfile directory.|readFile "config.yaml"
                readDir|readDir path → list|List file paths in a directory.|readDir "values"
                readDirEntries|readDirEntries path → entries|List directory entries, including directories.|readDirEntries "values"
                isFile|isFile path → bool|Check whether a path names a file.|isFile "values.yaml"
                isDir|isDir path → bool|Check whether a path names a directory.|isDir "values"
                exec|exec command args → string|Run a command during Helmfile rendering and return its output.|exec "git" (list "rev-parse" "HEAD")
                envExec|envExec environment command args → string|Run a command with additional environment variables.|envExec (dict "MODE" "prod") "./version" (list)
                toYaml|toYaml value → string|Serialize a value as YAML.|.Values.config | toYaml
                fromYaml|fromYaml text → value|Parse YAML text into a template value.|readFile "config.yaml" | fromYaml
                setValueAtPath|setValueAtPath path value map → map|Assign a value at a dotted map path.|.Values | setValueAtPath "image.tag" "v2"
                get|get path [default] map → value|Read a dotted path, optionally falling back to a default. Helmfile overrides Sprig's get.|.Values | get "image.tag" "latest"
                getOrNil|getOrNil path map → value|Read a dotted path, returning nil when it is absent.|.Values | getOrNil "optional.setting"
                tpl|tpl text context → string|Render a string as a Go template using the supplied context.|tpl "{{ .Environment.Name }}" .
                required|required message value → value|Fail rendering with a message when the value is empty.|.Values.domain | required "domain is required"
                include|include name context → string|Render a named template from local _*.tpl helper files.|include "app.labels" .
                fetchSecretValue|fetchSecretValue reference → value|Resolve a vals secret reference during Helmfile rendering.|fetchSecretValue "ref+vault://secret/app#/password"
                expandSecretRefs|expandSecretRefs value → value|Resolve vals references inside an object during rendering.|.Values | expandSecretRefs
                """);
        add("Go template", "https://pkg.go.dev/text/template", """
                if|if pipeline … [else …] end|Render a branch when its pipeline is nonempty.|if .Values.enabled
                else|else [if pipeline]|Select the alternative branch of if, range, or with.|else
                end|end|Close an if, range, with, define, or block action.|end
                range|range [$index, $value :=] pipeline … end|Iterate a collection; dot becomes the current item. Use $ to access the root.|range $name, $value := .Values.apps
                with|with pipeline … [else …] end|Set dot to a nonempty pipeline result within the block.|with .Values.image
                define|define "name" … end|Declare a reusable named template.|define "app.labels"
                template|template "name" [pipeline]|Execute a named template with an optional context.|template "app.labels" .
                block|block "name" pipeline … end|Define and execute a replaceable named template.|block "app.labels" .
                break|break|Stop the innermost range loop.|break
                continue|continue|Skip to the next range iteration.|continue
                index|index collection key [key …] → value|Look up a map key or array index, including keys containing dots or dashes.|index .Values "image-tag"
                slice|slice value [start [end [max]]] → slice|Extract a portion of an array, slice, or string.|slice .Values.apps 0 2
                len|len value → int|Return the length of a collection or string.|len .Values.apps
                printf|printf format args… → string|Format values using Go formatting verbs.|printf "%s-%s" .Environment.Name "api"
                print|print args… → string|Join formatted arguments.|print "api-" .Environment.Name
                println|println args… → string|Format arguments separated by spaces and append a newline.|println "hello" "world"
                eq|eq first rest… → bool|Check whether the first argument equals any subsequent argument.|eq .Environment.Name "production"
                ne|ne a b → bool|Check whether two values differ.|ne .Environment.Name "default"
                lt|lt a b → bool|Compare whether a is less than b.|lt (len .Values.apps) 2
                le|le a b → bool|Compare whether a is at most b.|le (len .Values.apps) 2
                gt|gt a b → bool|Compare whether a is greater than b.|gt (len .Values.apps) 0
                ge|ge a b → bool|Compare whether a is at least b.|ge (len .Values.apps) 1
                and|and args… → value|Return the first empty argument, or the last argument.|and .Values.enabled .Values.ready
                or|or args… → value|Return the first nonempty argument, or the last argument.|or .Values.name "api"
                not|not value → bool|Negate the truth value of an argument.|not .Values.disabled
                """);
        add("Sprig", "https://masterminds.github.io/sprig/", """
                default|default fallback value → value|Use the fallback when the value is empty; false and zero count as empty.|.Values.replicas | default 1
                empty|empty value → bool|Test for an empty value, including false, zero, and nil.|empty .Values.name
                coalesce|coalesce values… → value|Return the first nonempty argument.|coalesce .Values.name "api"
                ternary|ternary whenTrue whenFalse condition → value|Choose a value using a boolean condition.|ternary "prod" "dev" .Values.production
                quote|quote values… → string|Wrap values in double quotes.|.Values.image.tag | quote
                squote|squote values… → string|Wrap values in single quotes.|.Values.name | squote
                indent|indent width text → string|Indent every line by the given number of spaces.|.Values.config | toYaml | indent 4
                nindent|nindent width text → string|Add a newline and indent every line.|.Values.config | toYaml | nindent 4
                trim|trim text → string|Remove surrounding whitespace.|.Values.name | trim
                trimPrefix|trimPrefix prefix text → string|Remove a prefix when it is present.|.Values.name | trimPrefix "app-"
                trimSuffix|trimSuffix suffix text → string|Remove a suffix when it is present.|.Values.name | trimSuffix "-app"
                lower|lower text → string|Convert text to lowercase.|.Values.name | lower
                upper|upper text → string|Convert text to uppercase.|.Environment.Name | upper
                title|title text → string|Capitalize the first letter of each word.|.Values.name | title
                base|base path → string|Return the final component of a slash-separated path.|base "config/app.yaml"
                dir|dir path → string|Return the directory of a slash-separated path.|dir "config/app.yaml"
                replace|replace old new text → string|Replace every occurrence of a substring.|.Values.name | replace "_" "-"
                contains|contains substring text → bool|Check whether text contains a substring.|contains "prod" .Environment.Name
                hasPrefix|hasPrefix prefix text → bool|Check a string prefix.|hasPrefix "prod" .Environment.Name
                hasSuffix|hasSuffix suffix text → bool|Check a string suffix.|hasSuffix "-api" .Values.name
                trunc|trunc length text → string|Truncate a string to a maximum length.|.Values.name | trunc 63
                dict|dict key value … → map|Create a string-keyed map.|dict "name" "api" "enabled" true
                list|list values… → list|Create a list.|list "dev" "production"
                hasKey|hasKey map key → bool|Check whether a map contains a key.|hasKey .Values "image"
                sprigGet|sprigGet map key → value|Read a single map key using Sprig's original get behavior.|sprigGet .Values "name"
                dig|dig keys… fallback map → value|Traverse nested maps, using a fallback if a key is missing.|dig "image" "tag" "latest" .Values
                merge|merge destination sources… → map|Deep merge maps, preserving values from the destination.|merge (dict) .Values.defaults .Values.overrides
                mergeOverwrite|mergeOverwrite destination sources… → map|Deep merge maps with values from later sources taking priority.|mergeOverwrite (dict) .Values.defaults .Values.overrides
                deepCopy|deepCopy value → value|Copy nested structures before modifying them.|deepCopy .Values
                keys|keys maps… → list|Collect keys from one or more maps.|keys .Values | sortAlpha
                sortAlpha|sortAlpha list → list|Sort values alphabetically.|keys .Values | sortAlpha
                join|join separator list → string|Join list entries with a separator.|list "a" "b" | join ","
                splitList|splitList separator text → list|Split text into a list.|splitList "," "dev,production"
                first|first list → value|Return the first entry of a list.|first .Values.apps
                last|last list → value|Return the last entry of a list.|last .Values.apps
                rest|rest list → list|Return all entries after the first.|rest .Values.apps
                append|append list value → list|Create a list with one extra entry.|append .Values.apps "worker"
                concat|concat lists… → list|Combine lists in order.|concat .Values.apps (list "worker")
                uniq|uniq list → list|Remove duplicate entries.|.Values.apps | uniq
                toJson|toJson value → string|Serialize a value as JSON.|.Values.config | toJson
                fromJson|fromJson text → value|Parse JSON text.|readFile "config.json" | fromJson
                b64enc|b64enc text → string|Encode text as Base64.|.Values.name | b64enc
                b64dec|b64dec text → string|Decode Base64 text.|.Values.encoded | b64dec
                sha256sum|sha256sum text → string|Compute a SHA-256 digest.|readFile "config.yaml" | sha256sum
                int|int value → int|Convert a value to an integer.|.Values.replicas | int
                toString|toString value → string|Convert a value to text.|.Values.port | toString
                add|add numbers… → int|Add integer arguments.|add .Values.replicas 1
                sub|sub a b → int|Subtract b from a.|sub .Values.replicas 1
                mul|mul numbers… → int|Multiply integer arguments.|mul .Values.replicas 2
                div|div a b → int|Divide integers.|div .Values.replicas 2
                fail|fail message → error|Stop rendering with an error message.|fail "unsupported environment"
                regexMatch|regexMatch pattern text → bool|Check text against a regular expression.|regexMatch "^[a-z]+$" .Values.name
                semverCompare|semverCompare constraint version → bool|Test a semantic version constraint.|semverCompare ">=1.0.0" .Values.version
                """);
    }
    private static void add(String family, String url, String rows) {
        for (String row : rows.strip().split("\n")) {
            String[] cells = row.split("(?<!\\\\)\\|", 4);
            FUNCTIONS.put(cells[0], new Function(cells[0], cells[1], cells[2], cells[3].replace("\\|", "|"), url, family));
        }
    }
    public static Collection<Function> all() { return Collections.unmodifiableCollection(FUNCTIONS.values()); }
    public static Function find(String name) { return FUNCTIONS.get(name); }
    public static String escape(String text) { return StringUtil.escapeXmlEntities(text); }
    private FunctionCatalog() {}
}
