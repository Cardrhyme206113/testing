from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "java" / "com" / "example" / "blockhost"

replacements = {
    "ServerRepository.java": {
        "Files.readString(stateFile.toPath(), StandardCharsets.UTF_8)": "FileIo.readUtf8(stateFile)",
        "Files.readString(file.toPath(), StandardCharsets.UTF_8)": "FileIo.readUtf8(file)",
        "Files.writeString(file.toPath(), content, StandardCharsets.UTF_8)": "FileIo.writeUtf8(file, content)",
        "Files.writeString(tmp.toPath(), state.toString(2), StandardCharsets.UTF_8)": "FileIo.writeUtf8(tmp, state.toString(2))",
        "Files.writeString(stateFile.toPath(), state.toString(2), StandardCharsets.UTF_8)": "FileIo.writeUtf8(stateFile, state.toString(2))",
        "Files.writeString(file.toPath(), text, StandardCharsets.UTF_8)": "FileIo.writeUtf8(file, text)",
        "file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : \"\"": "file.isFile() ? FileIo.readUtf8(file) : \"\"",
    },
    "BlockHostBackend.java": {
        "Files.readString(file.toPath(),StandardCharsets.UTF_8)": "FileIo.readUtf8(file)",
    },
    "MinecraftServerService.java": {
        "processStats.sample(process.pid())": "processStats.sample(ProcessId.get(process))",
    },
}

for name, pairs in replacements.items():
    path = root / name
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in pairs.items():
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"patched {name}")
    else:
        print(f"no changes needed for {name}")
