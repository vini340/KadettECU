package kadettecu.comunicacao;

import kadettecu.protocolo.Protocolo;

/** A ECU recusou um comando (NACK) ou a comunicação falhou. */
public class ErroProtocolo extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int codigo;

    public ErroProtocolo(String mensagem) {
        super(mensagem);
        this.codigo = 0;
    }

    public ErroProtocolo(int codigo) {
        super("A ECU recusou o comando: " + Protocolo.descreverErro(codigo));
        this.codigo = codigo;
    }

    /** Código do NACK (veja Protocolo.ERRO_*), ou 0 se não veio de um NACK. */
    public int getCodigo() {
        return codigo;
    }
}
