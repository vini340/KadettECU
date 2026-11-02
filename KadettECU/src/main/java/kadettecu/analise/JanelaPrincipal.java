package kadettecu.analise;

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

public final class JanelaPrincipal extends JFrame implements ExibidorResultado {

    private static final long serialVersionUID = 1L; 

    private final transient ControladorECU controlador; // transient: o JFrame é Serializable, o controlador não
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
        areaTexto.append(RelatorioHex.montar(resultado));
    }
}
