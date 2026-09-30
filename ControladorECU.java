import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ControladorECU {

    private final List<ExibidorResultado> exibidores = new ArrayList<>();

    public void adicionarExibidor(ExibidorResultado exibidor) {
        exibidores.add(exibidor);
    }

    public void analisar(String caminho, int valorProcurado) {
        ArquivoECU arquivo = new ArquivoECU(caminho);
        System.out.println(arquivo.getCaminhoAbsoluto());
        if (!arquivo.existe()) {
            System.out.println("Arquivo não encontrado");
            return;
        }
        try {
            ResultadoAnalise resultado = new AnalisadorECU(arquivo).analisar(valorProcurado);
            for (ExibidorResultado exibidor : exibidores) {
                exibidor.exibir(resultado);
            }
        } catch (FileNotFoundException e) {
            System.out.println("Arquivo não encontrado");
        } catch (IOException e) {
            System.out.println("Erro ao ler o arquivo");
        }
    }
}
