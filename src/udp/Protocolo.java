package udp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

public class Protocolo {

    public static final int PORTA = 5000; //porta do sv
    public static final int TAM_BUFFER = 65507;  //tamanho do arquivo a ser criado antes de receber
    public static final int TAM_FRAGMENTO = 1024; //tamanho de cada parte do arquivo pra enviar
    public static final int TIMEOUT = 500;
    public static final int TENTATIVAS = 5;

    //forma de simular perdas de pacotes, pois a rede local eh mto dificil acontecer naturalmente
    public static double perda = 0;

    //usada para cliente e sv conseguir implementar os avisos da forma q quiser
    public interface Avisos {
        void aviso(String operacao, String detalhes);

        default void progresso(int porcentagem) {
        }
    }

    //envia texto sem confirmacao
    public static void enviar(DatagramSocket socket, String mensagem, SocketAddress destino) throws IOException {
        enviar(socket, mensagem.getBytes(StandardCharsets.UTF_8), destino);
    }

    //envia texto com confirmacao e arquivos
    public static void enviar(DatagramSocket socket, byte[] dados, SocketAddress destino) throws IOException {
        socket.send(new DatagramPacket(dados, dados.length, destino));
    }

    //transforma oq recebeu para texto
    public static String texto(DatagramPacket pacote) {
        return new String(pacote.getData(), 0, pacote.getLength(), StandardCharsets.UTF_8);
    }

    //forma de simular perdas de pacotes
    public static boolean descartar() {
        return Math.random() < perda;
    }

    //envia, espera retorno, tenta x vezes dps marca como erro
    public static DatagramPacket enviarEsperar(DatagramSocket socket, byte[] dados, SocketAddress destino,
            String esperado, Avisos avisos) throws IOException {
        socket.setSoTimeout(TIMEOUT);
        for (int tentativa = 1; tentativa <= TENTATIVAS; tentativa++) {
            if (tentativa > 1) {
                avisos.aviso("RETRANSMISSAO", "tentativa " + tentativa + "/" + TENTATIVAS + " esperando " + esperado);
            }
            enviar(socket, dados, destino);
            try {
                while (true) {
                    DatagramPacket resposta = new DatagramPacket(new byte[TAM_BUFFER], TAM_BUFFER);
                    socket.receive(resposta);
                    if (descartar()) {
                        avisos.aviso("PERDA", "pacote descartado (simulacao de perda)");
                        continue;
                    }
                    String texto = texto(resposta);
                    if (texto.equals(esperado) || texto.startsWith(esperado + "|") || texto.startsWith("ERROR|")) {
                        return resposta;
                    }
                }
            } catch (SocketTimeoutException e) {
                avisos.aviso("TIMEOUT", "sem resposta para " + esperado);
            }
        }
        throw new IOException("sem resposta depois de " + TENTATIVAS + " tentativas (conexao perdida)");
    }

    //recebe arquivo separado e envia as partes
    public static void enviarArquivo(DatagramSocket socket, SocketAddress destino, int id, byte[][] partes, Avisos avisos) throws IOException {
        for (int i = 0; i < partes.length; i++) {
            String resposta = texto(enviarEsperar(socket, montarData(id, i, partes[i]), destino, "ACK|" + id + "|" + i, avisos));
            if (resposta.startsWith("ERROR|")) {
                throw new IOException(resposta);
            }
            avisos.progresso((i + 1) * 100 / partes.length);
        }
    }

    //recebe parte por parte do arquivo, tratando erros e dps junta
    public static byte[] receberArquivo(DatagramSocket socket, SocketAddress origem, int total,
            String primeiraResposta, Avisos avisos) throws IOException {
        byte[][] partes = new byte[total][];
        int recebidos = 0;
        int tentativas = 0;
        String ultimaResposta = primeiraResposta;
        enviar(socket, ultimaResposta, origem);
        socket.setSoTimeout(TIMEOUT);

        while (recebidos < total) {
            DatagramPacket pacote = new DatagramPacket(new byte[TAM_BUFFER], TAM_BUFFER);
            try {
                socket.receive(pacote);
            } catch (SocketTimeoutException e) {
                tentativas++;
                if (tentativas == TENTATIVAS) {
                    throw new IOException("sem resposta depois de " + TENTATIVAS + " tentativas (conexao perdida)");
                }
                avisos.aviso("TIMEOUT", "esperando fragmento, reenviando " + ultimaResposta);
                enviar(socket, ultimaResposta, origem);
                continue;
            }
            if (descartar()) {
                avisos.aviso("PERDA", "pacote descartado (simulacao de perda)");
                continue;
            }
            tentativas = 0;

            if (!texto(pacote).startsWith("DATA|")) {
                enviar(socket, ultimaResposta, origem);
                continue;
            }

            int inicio = inicioPayload(pacote);
            String[] cabecalho = new String(pacote.getData(), 0, inicio, StandardCharsets.UTF_8).split("\\|");
            int numero = Integer.parseInt(cabecalho[2]);
            String ack = "ACK|" + cabecalho[1] + "|" + numero;

            if (partes[numero] != null) {
                avisos.aviso("DUPLICADO", "fragmento " + numero + " ja recebido, confirmado de novo");
                enviar(socket, ack, origem);
                continue;
            }
            if (numero != recebidos) {
                avisos.aviso("FORA_DE_ORDEM", "fragmento " + numero + " chegou fora de ordem");
            }
            partes[numero] = Arrays.copyOfRange(pacote.getData(), inicio, pacote.getLength());
            recebidos++;
            avisos.progresso(recebidos * 100 / total);

            if (recebidos < total) {
                ultimaResposta = ack;
                enviar(socket, ack, origem);
            }
        }

        return juntar(partes);
    }

    //eh usado por qm recebe o arquivo, responde a ultima parte e espera um pco, pra ver c chegou
    public static void confirmarUltimo(DatagramSocket socket, SocketAddress origem, String resposta) throws IOException {
        enviar(socket, resposta, origem);
        socket.setSoTimeout(TIMEOUT * 2);
        try {
            while (true) {
                socket.receive(new DatagramPacket(new byte[TAM_BUFFER], TAM_BUFFER));
                enviar(socket, resposta, origem);
            }
        } catch (SocketTimeoutException e) {
        }
    }

    //separa o arquivo
    public static byte[][] fragmentar(byte[] arquivo) {
        int total = Math.max(1, (arquivo.length + TAM_FRAGMENTO - 1) / TAM_FRAGMENTO);
        byte[][] partes = new byte[total][];
        for (int i = 0; i < total; i++) {
            partes[i] = Arrays.copyOfRange(arquivo, i * TAM_FRAGMENTO, Math.min(arquivo.length, (i + 1) * TAM_FRAGMENTO));
        }
        return partes;
    }

    //junta as partes do arquivo
    public static byte[] juntar(byte[][] partes) {
        ByteArrayOutputStream arquivo = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            arquivo.writeBytes(parte);
        }
        return arquivo.toByteArray();
    }

    //cria identificador e adiciona em cada parte do arquivo separado
    public static byte[] montarData(int id, int numero, byte[] parte) {
        byte[] cabecalho = ("DATA|" + id + "|" + numero + "|").getBytes(StandardCharsets.UTF_8);
        byte[] dados = Arrays.copyOf(cabecalho, cabecalho.length + parte.length);
        System.arraycopy(parte, 0, dados, cabecalho.length, parte.length);
        return dados;
    }

    //funcao auxiliar para separar os identificadores dos pacotes
    public static int inicioPayload(DatagramPacket pacote) {
        byte[] dados = pacote.getData();
        int barras = 0;
        for (int i = 0; i < pacote.getLength(); i++) {
            if (dados[i] == '|' && ++barras == 3) {
                return i + 1;
            }
        }
        return -1;
    }

    //gera hash do arquivo para saber c ele chegou da mesma forma q foi enviado
    public static String hash(byte[] dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
