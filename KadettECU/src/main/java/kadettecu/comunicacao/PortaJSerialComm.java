package kadettecu.comunicacao;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Porta USB/serial real, usando a biblioteca jSerialComm. É a única classe que a conhece. */
public class PortaJSerialComm implements PortaSerial {

    private static final int TEMPO_LEITURA_MS = 100;
    private static final int TEMPO_ESCRITA_MS = 200;

    private final String nomeSistema;
    private final int baud;
    private SerialPort porta;

    /** @param nomeSistema nome do sistema, como "COM3" (Windows) ou "/dev/ttyUSB0" (Linux) */
    public PortaJSerialComm(String nomeSistema, int baud) {
        this.nomeSistema = nomeSistema;
        this.baud = baud;
    }

    /** Uma porta encontrada no computador: nome do sistema + descrição amigável. */
    public static final class PortaEncontrada {
        private final String nomeSistema;
        private final String descricao;

        PortaEncontrada(String nomeSistema, String descricao) {
            this.nomeSistema = nomeSistema;
            this.descricao = descricao;
        }

        public String getNomeSistema() {
            return nomeSistema;
        }

        public String getDescricao() {
            return descricao;
        }

        @Override
        public String toString() {
            return nomeSistema + " - " + descricao;
        }
    }

    public static List<PortaEncontrada> listarPortas() {
        List<PortaEncontrada> portas = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            portas.add(new PortaEncontrada(p.getSystemPortName(), p.getDescriptivePortName()));
        }
        return portas;
    }

    @Override
    public String getNome() {
        return nomeSistema;
    }

    @Override
    public void abrir() throws IOException {
        try {
            porta = SerialPort.getCommPort(nomeSistema);
        } catch (RuntimeException e) {
            throw new IOException("Porta inválida: " + nomeSistema, e);
        }
        porta.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        // semi-bloqueante: readBytes devolve assim que chega 1 byte, ou 0 após 100 ms sem dados
        porta.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                TEMPO_LEITURA_MS, TEMPO_ESCRITA_MS);
        if (!porta.openPort()) {
            throw new IOException("Não foi possível abrir " + nomeSistema
                    + " (porta ocupada, sem permissão ou desconectada)");
        }
    }

    @Override
    public boolean estaAberta() {
        return porta != null && porta.isOpen();
    }

    @Override
    public int ler(byte[] buffer) throws IOException {
        if (!estaAberta()) {
            return -1;
        }
        return porta.readBytes(buffer, buffer.length); // -1 em erro (por exemplo, cabo USB removido)
    }

    @Override
    public void escrever(byte[] dados) throws IOException {
        if (!estaAberta()) {
            throw new IOException("Porta fechada");
        }
        int escritos = porta.writeBytes(dados, dados.length);
        if (escritos != dados.length) {
            throw new IOException("Falha ao escrever na porta (" + escritos + " de " + dados.length + " bytes)");
        }
    }

    @Override
    public void fechar() {
        if (porta != null) {
            porta.closePort();
        }
    }
}
