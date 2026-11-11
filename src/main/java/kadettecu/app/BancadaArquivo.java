package kadettecu.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Bancada do firmware em C rodando no PC ({@code ecu_host}): esse programa relê um arquivo de
 * texto ("chave=valor") sempre que ele muda, então basta reescrevê-lo. A escrita é feita num
 * arquivo temporário que depois substitui o definitivo, para o programa nunca ler um arquivo
 * pela metade.
 */
public final class BancadaArquivo implements Bancada {

    private final Path arquivo;
    private boolean manual;

    public BancadaArquivo(Path arquivo) throws IOException {
        this.arquivo = arquivo;
        escrever(EntradasBancada.padrao());
    }

    @Override
    public void definir(EntradasBancada e) throws IOException {
        manual = true;
        escrever(e);
    }

    @Override
    public void desligar() throws IOException {
        manual = false;
        escrever(EntradasBancada.padrao());
    }

    @Override
    public boolean temCicloAutomatico() {
        return false;
    }

    @Override
    public boolean estaManual() {
        return manual;
    }

    private void escrever(EntradasBancada e) throws IOException {
        String texto = String.format(Locale.ROOT,
                "rpm=%.1f vel=%.1f tps=%.1f ect=%.1f iat=%.1f map=%.1f vbat=%.3f oleo=%.2f lambda=%.3f two_step=0 launch=0%n",
                e.getRpm(), e.getVelocidadeKmh(), e.getTpsPct(), e.getEctC(), e.getIatC(), e.getMapKpa(),
                e.getBateriaV(), e.getOleoBar(), e.getLambda());
        Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
        Files.write(temporario, texto.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void fechar() {
        try {
            Files.deleteIfExists(arquivo);
            Files.deleteIfExists(arquivo.resolveSibling(arquivo.getFileName() + ".tmp"));
        } catch (IOException e) {
            // arquivo temporário que sobrar será limpo pelo sistema
        }
    }
}
