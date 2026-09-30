import java.util.Scanner;

public class InterfaceConsole implements ExibidorResultado {

    private static final int OPCAO_ANALISAR = 1;
    private static final int OPCAO_SAIR = 2;

    private final ControladorECU controlador;
    private final Scanner scanner = new Scanner(System.in);

    public InterfaceConsole(ControladorECU controlador) {
        this.controlador = controlador;
    }

    public void iniciar() {
        int opcao = 0;

        while (opcao != OPCAO_SAIR) {
            exibirMenu();
            opcao = scanner.nextInt();
            scanner.nextLine();

            switch (opcao) {
                case OPCAO_ANALISAR:
                    analisarPeloConsole();
                    break;
                case OPCAO_SAIR:
                    System.out.println("Saindo...");
                    break;
                default:
                    System.out.println("Opção invalida");
                    break;
            }
        }
    }

    private void exibirMenu() {
        System.out.println("1 - Analisar ECU");
        System.out.println("2 - Sair");
    }

    private void analisarPeloConsole() {
        System.out.println("Digite o nome do arquivo: ");
        String caminho = scanner.nextLine();
        System.out.println("Digite o endereço que deseja consultar:");
        int valor = scanner.nextInt();
        controlador.analisar(caminho, valor);
    }

    @Override
    public void exibir(ResultadoAnalise resultado) {
        for (LinhaHex linha : resultado.getLinhas()) {
            if (linha.estaCompleta()) {
                System.out.println(linha.formatarLinha());
                System.out.println();
            }
        }
        ArquivoECU arquivo = resultado.getArquivo();
        System.out.println("Arquivo encontrado");
        System.out.println("Tamanho :" + arquivo.getTamanho() + "bytes");
        System.out.println("Analisando ECU : " + arquivo.getCaminho());
        System.out.println("Quantidade de bytes: " + resultado.getQuantidadeBytes());
    }
}
