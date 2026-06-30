package com.example.blockhost;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class FileIo {
    private FileIo() {}
    public static String readUtf8(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    public static void writeUtf8(File file,String text) throws IOException { File parent=file.getParentFile();if(parent!=null)parent.mkdirs();Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8)); }
    public static String readUtf8(InputStream input) throws IOException { ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[32768];int read;while((read=input.read(buffer))>=0)output.write(buffer,0,read);return new String(output.toByteArray(),StandardCharsets.UTF_8); }
}
