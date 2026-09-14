"""Package the public build-baseline engine for testing private samples locally."""
import os
from pathlib import Path
import shutil
import zipfile

out = Path('build/decompiler-kit')
roots = [Path.cwd(), Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))]
# Public runtime dependency for invoking the built plugin's IO-only export path locally.
for root in roots:
    candidates = list(root.glob('**/kotlin-stdlib-1.9.25.jar'))
    if candidates:
        shutil.copyfile(candidates[0], out / 'kotlin-stdlib.jar')
        break
for root in roots:
    for folder, _, names in os.walk(root):
        for name in names:
            if 'decompiler' not in name.lower() or not name.endswith('.jar'):
                continue
            source = Path(folder) / name
            with zipfile.ZipFile(source) as archive:
                if 'org/jetbrains/java/decompiler/main/decompiler/BaseDecompiler.class' not in archive.namelist():
                    continue
            shutil.copyfile(source, out / 'engine.jar')
            (out / 'README.txt').write_text(
                'IDEA build-baseline Fernflower diagnostic tool. No user samples are included.\n'
                'Run: jre/bin/java -cp .:engine.jar DecompilerProbe input.class internal/name output.java true\n'
                'Repeat with false to disable generic signature reconstruction.\n'
                'This parses bytecode; it does not load or execute the supplied class.\n'
                'Fernflower is part of JetBrains IntelliJ Community (Apache License 2.0).\n'
                'JRE licenses are included under jre/legal.\n', encoding='utf-8')
            print('Packaged build-baseline Fernflower engine')
            raise SystemExit(0)
raise SystemExit('Bundled Fernflower engine JAR was not found')
