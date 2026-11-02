package kadettecu.modelo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A "calibração" completa (o que um preparador chama de tune): eixos, as três tabelas e os
 * parâmetros do motor. Pode ser salva e aberta em arquivo de texto (.kecu).
 */
public final class Calibracao {

    private static final String CABECALHO = "# KadettECU - calibracao (formato 1)";

    private final EixosMapa eixos;
    private final MapaECU[] mapas;
    private final ParametrosMotor parametros;

    public Calibracao(EixosMapa eixos, MapaECU[] mapas, ParametrosMotor parametros) {
        if (mapas.length != TipoMapa.values().length) {
            throw new IllegalArgumentException("São esperados " + TipoMapa.values().length + " mapas");
        }
        this.eixos = eixos;
        this.mapas = mapas.clone();
        this.parametros = parametros;
    }

    public static Calibracao padrao() {
        MapaECU[] mapas = new MapaECU[TipoMapa.values().length];
        for (TipoMapa t : TipoMapa.values()) {
            mapas[t.getId()] = MapaECU.padrao(t);
        }
        return new Calibracao(EixosMapa.padrao(), mapas, ParametrosMotor.padrao());
    }

    public Calibracao copia() {
        MapaECU[] m = new MapaECU[mapas.length];
        for (int i = 0; i < m.length; i++) {
            m[i] = mapas[i].copia();
        }
        return new Calibracao(eixos.copia(), m, parametros.copia());
    }

    public EixosMapa getEixos() {
        return eixos;
    }

    public MapaECU getMapa(TipoMapa tipo) {
        return mapas[tipo.getId()];
    }

    public ParametrosMotor getParametros() {
        return parametros;
    }

    public void salvar(Path destino) throws IOException {
        List<String> linhas = new ArrayList<>();
        linhas.add(CABECALHO);
        for (CampoParametro c : CampoParametro.values()) {
            linhas.add("parametro." + c.name() + "=" + parametros.get(c));
        }
        linhas.add("eixo.rpm=" + juntar(eixos.getRpmArray()));
        linhas.add("eixo.carga=" + juntar(eixos.getCargaArray()));
        for (TipoMapa t : TipoMapa.values()) {
            for (int l = 0; l < MapaECU.TAMANHO; l++) {
                int[] valores = new int[MapaECU.TAMANHO];
                for (int c = 0; c < MapaECU.TAMANHO; c++) {
                    valores[c] = getMapa(t).getRaw(l, c);
                }
                linhas.add("mapa." + t.name() + "." + l + "=" + juntar(valores));
            }
        }
        Files.write(destino, linhas, StandardCharsets.UTF_8);
    }

    /** Lê um arquivo .kecu. O que não estiver no arquivo fica com o valor padrão. */
    public static Calibracao carregar(Path origem) throws IOException {
        Calibracao cal = padrao();
        int[] rpm = cal.eixos.getRpmArray();
        int[] carga = cal.eixos.getCargaArray();
        try {
            for (String linha : Files.readAllLines(origem, StandardCharsets.UTF_8)) {
                String t = linha.trim();
                int igual = t.indexOf('=');
                if (t.isEmpty() || t.startsWith("#") || igual < 0) {
                    continue;
                }
                String chave = t.substring(0, igual);
                String valor = t.substring(igual + 1);
                if (chave.startsWith("parametro.")) {
                    aplicarParametro(cal, chave.substring(10), valor);
                } else if (chave.equals("eixo.rpm")) {
                    rpm = separar(valor);
                } else if (chave.equals("eixo.carga")) {
                    carga = separar(valor);
                } else if (chave.startsWith("mapa.")) {
                    aplicarLinhaDeMapa(cal, chave.substring(5), valor);
                }
            }
            EixosMapa novos = EixosMapa.deValores(rpm, carga);
            return new Calibracao(novos, cal.mapas, cal.parametros);
        } catch (IllegalArgumentException e) {
            throw new IOException("Arquivo de calibração inválido: " + e.getMessage(), e);
        }
    }

    private static void aplicarParametro(Calibracao cal, String nome, String valor) {
        for (CampoParametro c : CampoParametro.values()) {
            if (c.name().equals(nome)) {
                cal.parametros.set(c, Integer.parseInt(valor.trim()));
                return;
            }
        }
    }

    private static void aplicarLinhaDeMapa(Calibracao cal, String chave, String valor) {
        int ponto = chave.lastIndexOf('.');
        TipoMapa tipo = TipoMapa.valueOf(chave.substring(0, ponto));
        int linha = Integer.parseInt(chave.substring(ponto + 1));
        int[] valores = separar(valor);
        if (linha < 0 || linha >= MapaECU.TAMANHO || valores.length != MapaECU.TAMANHO) {
            throw new IllegalArgumentException("linha de mapa inválida: " + chave);
        }
        for (int c = 0; c < MapaECU.TAMANHO; c++) {
            cal.getMapa(tipo).setRaw(linha, c, valores[c]);
        }
    }

    private static String juntar(int[] valores) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < valores.length; i++) {
            sb.append(i == 0 ? "" : ",").append(valores[i]);
        }
        return sb.toString();
    }

    private static int[] separar(String texto) {
        String[] partes = texto.trim().split(",");
        int[] v = new int[partes.length];
        for (int i = 0; i < partes.length; i++) {
            v[i] = Integer.parseInt(partes[i].trim());
        }
        return v;
    }
}
