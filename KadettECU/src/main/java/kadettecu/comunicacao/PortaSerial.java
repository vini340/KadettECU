package kadettecu.comunicacao;

import java.io.IOException;

/**
 * Abstração de uma porta serial. A conexão com a ECU só conhece esta interface; por trás
 * dela pode estar a porta USB real (jSerialComm) ou a ECU simulada.
 */
public interface PortaSerial {

    String getNome();

    /** Abre a porta. Lança IOException se não for possível. */
    void abrir() throws IOException;

    boolean estaAberta();

    /**
     * Lê até buffer.length bytes. Bloqueia por no máximo ~100 ms.
     * @return bytes lidos; 0 se o tempo esgotou sem dados; -1 se a porta foi fechada ou caiu
     */
    int ler(byte[] buffer) throws IOException;

    void escrever(byte[] dados) throws IOException;

    void fechar();
}
