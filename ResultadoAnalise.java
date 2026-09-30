import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ResultadoAnalise {

    private final ArquivoECU arquivo;
    private final int valorProcurado;
    private final int quantidadeBytes;
    private final List<Integer> enderecos;
    private final List<LinhaHex> linhas;

    public ResultadoAnalise(ArquivoECU arquivo, int valorProcurado, int quantidadeBytes,
                            List<Integer> enderecos, List<LinhaHex> linhas) {
        this.arquivo = arquivo;
        this.valorProcurado = valorProcurado;
        this.quantidadeBytes = quantidadeBytes;
        this.enderecos = Collections.unmodifiableList(new ArrayList<>(enderecos));
        this.linhas = Collections.unmodifiableList(new ArrayList<>(linhas));
    }

    public ArquivoECU getArquivo() {
        return arquivo;
    }

    public int getValorProcurado() {
        return valorProcurado;
    }

    public int getQuantidadeBytes() {
        return quantidadeBytes;
    }

    public List<Integer> getEnderecos() {
        return enderecos;
    }

    public List<LinhaHex> getLinhas() {
        return linhas;
    }

    public boolean encontrouOcorrencias() {
        return !enderecos.isEmpty();
    }
}
