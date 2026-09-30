import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class ArquivoECU {

    private final String caminho;
    private final File arquivo;

    public ArquivoECU(String caminho) {
        this.caminho = caminho;
        this.arquivo = new File(caminho);
    }

    public String getCaminho() {
        return caminho;
    }

    public String getCaminhoAbsoluto() {
        return arquivo.getAbsolutePath();
    }

    public boolean existe() {
        return arquivo.exists();
    }

    public long getTamanho() {
        return arquivo.length();
    }

    public byte[] lerBytes() throws IOException {
        try (FileInputStream leitor = new FileInputStream(arquivo)) {
            return leitor.readAllBytes();
        }
    }
}
