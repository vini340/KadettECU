import java.util.Scanner;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.swing.JFrame;
import javax.swing.JButton;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import javax.swing.JFileChooser;
import javax.swing.JTextArea;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.JScrollPane;
import javax.swing.JOptionPane;
import java.util.ArrayList;

public class inicio {
    public static void main(String[] args) {
        System.out.println("KadettECU");
        System.out.println("Versão 0.1");
        JFrame janela = new JFrame();
        JButton botao = new JButton("Analisar ECU");
        JTextArea tex = new JTextArea();
        JScrollPane scroll = new JScrollPane(tex);
        tex.setFont(new Font("Monospaced", Font.BOLD, 14));
        JPanel painel = new JPanel();
        painel.setLayout(new BorderLayout());
        janela.setSize(800, 600);
        tex.setSize(500,300);
        janela.setTitle("KadettECU");
        janela.add(painel);
        painel.add(botao, BorderLayout.NORTH);
        painel.add(scroll, BorderLayout.CENTER);
        botao.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser seletor = new JFileChooser();
                int res = seletor.showOpenDialog(janela);
                if (res == JFileChooser.APPROVE_OPTION) {
                    File arquivo = seletor.getSelectedFile();
                    String entrada = JOptionPane.showInputDialog(janela,"Digite o HEX que deseja procurar:");
                    tex.setText("");
                    try {
                        int hexProcurado = Integer.parseInt(entrada, 16);
                        analisarECU(arquivo.getAbsolutePath(), tex, hexProcurado);
                    } catch (NumberFormatException erro) {
                        JOptionPane.showMessageDialog(janela,"HEX inválido!");
                    }
                }
            }
            
        });
        janela.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        janela.setVisible(true);

        Scanner scanner = new Scanner (System.in);
        int opcao = 0;

        while(opcao != 2) {
            menu();
            opcao = scanner.nextInt();
            scanner.nextLine();

        switch (opcao) {

            case 1:
                System.out.println("Digite o nome do arquivo: ");
                String arquivo = scanner.nextLine();
                System.out.println("Digite o endereço que deseja consultar:");
                int where = scanner.nextInt();
                analisarECU(arquivo, tex, where);
                break;
            case 2:
                System.out.println("Saindo...");
                break;
            default:
                System.out.println("Opção invalida");
                break;

        }
        }
    }
    public static void menu() {
        System.out.println("1 - Analisar ECU");
        System.out.println("2 - Sair");
    }   
    public static void analisarECU(String arquivo, JTextArea tex, int hexProcurado) {
        File file = new File(arquivo);
        System.out.println(file.getAbsolutePath());
        if (file.exists()){
            long tamanho = file.length();
            try (FileInputStream leitor = new FileInputStream(file)){
                 String hex;
                 String enderecoLinha = "";
                 int byteAtual = leitor.read();
                 int contador = 0;
                 int offset = 0;
                 String trunk = "";
                 String bau = "";
                 ArrayList<Integer> enderecos = new ArrayList<>();
                 StringBuilder viewer = new StringBuilder();
                 tex.append("========================================\n");
                 tex.append("           RESULTADO DA BUSCA\n");
                 tex.append("========================================\n\n");
                 viewer.append(LinhaHex.cabecalho() + "\n");
                 while (byteAtual != -1){
                     String ASCII;
                     if (contador % 16 == 0) {
                        enderecoLinha = String.format("%08X", offset);
                     }
                     if (byteAtual >= 32 && byteAtual <= 126){
                        ASCII = (char) byteAtual + "";
                     }else{
                        ASCII = ".";
                     }
                     trunk = trunk + ASCII;
                     hex = String.format("%02X", byteAtual);
                     bau = bau + hex + " ";  
                     contador ++;
                     if(contador % 16 == 8){
                        bau = bau + "| ";
                     }
                     if (byteAtual == hexProcurado) {
                        enderecos.add(offset);
                    }
                     offset ++;
                     if (contador % 16 == 0){
                        LinhaHex linha = new LinhaHex(enderecoLinha, bau, trunk);
                        System.out.println(linha.formatarLinha());
                        viewer.append(linha.formatarLinha() + "\n");
                        trunk = "";
                        bau = "";
                        System.out.println();
                     }
                     byteAtual = leitor.read();
                 }
                    if (!trunk.equals("")) {
                        LinhaHex linha = new LinhaHex(enderecoLinha, bau, trunk);
                       viewer.append(linha.formatarLinha() + "\n");
                    }
                    if (enderecos.isEmpty()) {
                        tex.append("Nenhuma ocorrência encontrada.\n\n");
                    }else{
                    tex.append(String.format("%-12s %-8s %s%n", "ENDEREÇO", "HEX", "ASCII"));
                       for (int i = 0; i < enderecos.size(); i++) {
                            tex.append(String.format(
                    "%-12d %-8s %c%n",
                            enderecos.get(i),
                            String.format("%02X", hexProcurado),
                            (char) hexProcurado
                            ));
                        }
                    }
                    tex.append("========================================\n");
                    tex.append("              HEX VIEWER\n");
                    tex.append("========================================\n");
                    tex.append(viewer.toString());
                 System.out.println("Arquivo encontrado");
                 System.out.println("Tamanho :" + tamanho + "bytes");
                 System.out.println("Analisando ECU : " + arquivo);
                 System.out.println("Quantidade de bytes: " + contador);
            }catch(FileNotFoundException e){
                System.out.println("Arquivo não encontrado");
            }catch(IOException e){
                System.out.println("Erro ao ler o arquivo");
            }
        }else{
            System.out.println("Arquivo não encontrado");
        }
    }
}
