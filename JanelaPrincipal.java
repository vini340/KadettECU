import java.awt.BorderLayout;
import java.awt.Font;
import java.io.File;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public class JanelaPrincipal extends JFrame implements ExibidorResultado {

    private static final long serialVersionUID = 1L; 

    private final ControladorECU controlador;
    private final JButton botaoAnalisar = new JButton("Analisar ECU");
    private final JTextArea areaTexto = new JTextArea();

    public JanelaPrincipal(ControladorECU controlador) {
        this.controlador = controlador;
        montarTela();
    }

    private void montarTela() {
        areaTexto.setFont(new Font("Monospaced", Font.BOLD, 14));

        JPanel painel = new JPanel(new BorderLayout());
        painel.add(botaoAnalisar, BorderLayout.NORTH);
        painel.add(new JScrollPane(areaTexto), BorderLayout.CENTER);

        setSize(800, 600);
        setTitle("KadettECU");
        add(painel);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        botaoAnalisar.addActionListener(e -> aoClicarEmAnalisar());
    }

    private void aoClicarEmAnalisar() {
        JFileChooser seletor = new JFileChooser();
        int res = seletor.showOpenDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File arquivo = seletor.getSelectedFile();
        String entrada = JOptionPane.showInputDialog(this, "Digite o HEX que deseja procurar:");
        areaTexto.setText("");

        int hexProcurado;
        try {
            hexProcurado = Integer.parseInt(entrada, 16);
        } catch (NumberFormatException erro) {
            JOptionPane.showMessageDialog(this, "HEX inválido!");
            return;
        }
        controlador.analisar(arquivo.getAbsolutePath(), hexProcurado);
    }

    @Override
    public void exibir(ResultadoAnalise resultado) {
        areaTexto.append(montarRelatorio(resultado));
    }

    private String montarRelatorio(ResultadoAnalise resultado) {
        StringBuilder texto = new StringBuilder();
        texto.append("========================================\n");
        texto.append("           RESULTADO DA BUSCA\n");
        texto.append("========================================\n\n");

        if (resultado.encontrouOcorrencias()) {
            texto.append(String.format("%-12s %-8s %s%n", "ENDEREÇO", "HEX", "ASCII"));
            for (int endereco : resultado.getEnderecos()) {
                texto.append(String.format("%-12d %-8s %c%n",
                        endereco,
                        String.format("%02X", resultado.getValorProcurado()),
                        (char) resultado.getValorProcurado()));
            }
        } else {
            texto.append("Nenhuma ocorrência encontrada.\n\n");
        }

        texto.append("========================================\n");
        texto.append("              HEX VIEWER\n");
        texto.append("========================================\n");
        texto.append(LinhaHex.cabecalho()).append("\n");
        for (LinhaHex linha : resultado.getLinhas()) {
            texto.append(linha.formatarLinha()).append("\n");
        }
        return texto.toString();
    }
}
