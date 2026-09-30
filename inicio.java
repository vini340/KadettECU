public class inicio {

    public static void main(String[] args) {
        System.out.println("KadettECU");
        System.out.println("Versão 0.1");

        ControladorECU controlador = new ControladorECU();
        JanelaPrincipal janela = new JanelaPrincipal(controlador);
        InterfaceConsole console = new InterfaceConsole(controlador);

        controlador.adicionarExibidor(janela);
        controlador.adicionarExibidor(console);

        janela.setVisible(true);
        console.iniciar();
    }
}
