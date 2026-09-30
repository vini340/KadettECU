import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AnalisadorECU {

    private final ArquivoECU arquivo;

    public AnalisadorECU(ArquivoECU arquivo) {
        this.arquivo = arquivo;
    }

    public ResultadoAnalise analisar(int valorProcurado) throws IOException {
        byte[] dados = arquivo.lerBytes();
        List<Integer> enderecos = buscarOcorrencias(dados, valorProcurado);
        List<LinhaHex> linhas = montarLinhas(dados);
        return new ResultadoAnalise(arquivo, valorProcurado, dados.length, enderecos, linhas);
    }

    private List<Integer> buscarOcorrencias(byte[] dados, int valorProcurado) {
        List<Integer> enderecos = new ArrayList<>();
        for (int offset = 0; offset < dados.length; offset++) {
            if ((dados[offset] & 0xFF) == valorProcurado) {
                enderecos.add(offset);
            }
        }
        return enderecos;
    }

    private List<LinhaHex> montarLinhas(byte[] dados) {
        List<LinhaHex> linhas = new ArrayList<>();
        for (int inicio = 0; inicio < dados.length; inicio += LinhaHex.BYTES_POR_LINHA) {
            int fim = Math.min(inicio + LinhaHex.BYTES_POR_LINHA, dados.length);
            linhas.add(new LinhaHex(inicio, Arrays.copyOfRange(dados, inicio, fim)));
        }
        return linhas;
    }
}
